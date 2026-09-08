package com.dugcanlift.macrocalc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dugcanlift.macrocalc.data.OutdoorActivityType
import com.dugcanlift.macrocalc.data.RoutePoint
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps the plain AOSP [LocationManager] (no Play Services, no
 * FusedLocationProviderClient — see the plan's architecture note) to record a
 * live route while a Run/Hike is in progress.
 *
 * Quality filter mirrors `LocationTracker.swift` exactly (plan's Global
 * Constraints): reject a fix if its accuracy is negative or worse than
 * [MAX_ACCURACY_METERS], or if it's already older than [MAX_FIX_AGE_MS] by
 * the time it's delivered. Updates are requested no closer together than
 * [MIN_DISTANCE_M], matching iOS's `distanceFilter`.
 *
 * A run has to keep recording after the phone locks or the user switches
 * apps, which on Android means a foreground service with a persistent
 * notification — there is no way around that ceremony for background
 * location. [LocationRecordingService] exists purely to hold that
 * notification and the process's foreground priority; the actual
 * `LocationManager` registration lives here, in the same process, because
 * splitting "receive location updates" and "hold the OS's attention" across
 * a bound service would add ceremony without changing behavior for a
 * single-process app like this one.
 *
 * Permission handling is deliberately NOT this class's job beyond a plain
 * granted/not-granted check in [start]. `ACCESS_BACKGROUND_LOCATION` needs
 * its own separate runtime-permission step the OS won't let a caller combine
 * with the foreground location request — that flow belongs to the recording
 * screen (a later task), not here.
 */
class LocationTracker private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _routePoints = MutableStateFlow<List<RoutePoint>>(emptyList())
    val routePoints: StateFlow<List<RoutePoint>> = _routePoints.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /**
     * The activity type of the in-progress recording, or `null` when idle.
     * This (together with [isRecording] and [startedAtEpochMs]) is the
     * source of truth for "is there an active recording" — living here
     * rather than on the Compose recording screen means a live recording
     * survives that screen briefly leaving composition (a bottom-tab switch)
     * or being torn down and recreated (a process restart while the
     * foreground service and GPS registration are still alive).
     */
    private val _activityType = MutableStateFlow<OutdoorActivityType?>(null)
    val activityType: StateFlow<OutdoorActivityType?> = _activityType.asStateFlow()

    /** Wall-clock start time of the in-progress recording. See [_activityType]'s doc comment. */
    private val _startedAtEpochMs = MutableStateFlow<Long?>(null)
    val startedAtEpochMs: StateFlow<Long?> = _startedAtEpochMs.asStateFlow()

    /**
     * True once the OS reports the active provider was switched off mid-recording
     * (e.g. the user toggled Location off from Quick Settings). Reset on the next
     * successful [start]. A recording is not stopped automatically when this fires —
     * [LocationManager] simply stops delivering updates — this exists purely so the
     * UI can show the user why their route has gone quiet instead of leaving it a
     * silent no-op.
     */
    private val _providerDisabled = MutableStateFlow(false)
    val providerDisabled: StateFlow<Boolean> = _providerDisabled.asStateFlow()

    /**
     * Carries the last known altitude forward across fixes that lack one, so a
     * momentarily-missing altitude reading never looks like "sea level" to
     * [com.dugcanlift.macrocalc.data.OutdoorActivityMath.elevationGainMeters]'s
     * hysteresis filter (see I-11 in the final-review fix wave).
     */
    private var lastKnownAltitudeMeters = 0.0

    /**
     * Explicit anonymous [LocationListener] rather than a SAM lambda: on API
     * 26-29 (this app's `minSdk`), `onStatusChanged`/`onProviderEnabled`/
     * `onProviderDisabled`/`onFlushComplete` are still abstract members of the
     * platform interface (they only became `default` methods in API 30), so a
     * SAM-converted lambda generates a class implementing only
     * `onLocationChanged` and crashes with `AbstractMethodError` the first
     * time the OS calls any of the other three — routine during real GPS use
     * (satellite acquisition/loss fires `onStatusChanged`; toggling Location
     * off fires `onProviderDisabled`).
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {
            _providerDisabled.value = false
        }
        override fun onProviderDisabled(provider: String) {
            _providerDisabled.value = true
        }
    }

    /**
     * Starts recording [activityType]: registers for location updates and
     * promotes the process via [LocationRecordingService]. Returns `false`
     * without side effects if foreground location permission isn't granted
     * yet, or if GPS is disabled, or if the OS refuses the foreground-service
     * start (e.g. restrictions on starting a foreground service while
     * backgrounded).
     *
     * A call for the *same* [activityType] while already recording is a
     * no-op success (idempotent re-entrant call). A call for a *different*
     * [activityType] while already recording first stops the in-progress
     * recording — a caller-supplied type that doesn't match what's already
     * running must never silently continue the old route into what looks
     * like a brand new recording (see C-2 in the final-review fix wave).
     */
    @SuppressLint("MissingPermission") // checked explicitly via hasForegroundLocationPermission()
    fun start(activityType: OutdoorActivityType): Boolean {
        if (_isRecording.value) {
            if (_activityType.value == activityType) return true
            stop()
        }
        if (!hasForegroundLocationPermission()) return false

        // NETWORK_PROVIDER is deliberately not used as a fallback here: every
        // network fix gets rejected by the accuracy filter below (~50m vs.
        // typical network-location accuracy of hundreds of meters to
        // kilometers), so it was a silent dead branch that recorded an empty
        // route while still running the foreground service. GPS-off is
        // reported as a plain failure instead (I-12 in the final-review fix
        // wave).
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false

        _routePoints.value = emptyList()
        _providerDisabled.value = false
        lastKnownAltitudeMeters = 0.0

        return try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper()
            )
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LocationRecordingService::class.java)
            )
            _activityType.value = activityType
            _startedAtEpochMs.value = System.currentTimeMillis()
            _isRecording.value = true
            true
        } catch (e: SecurityException) {
            locationManager.removeUpdates(listener)
            false
        } catch (e: IllegalStateException) {
            // e.g. ForegroundServiceStartNotAllowedException on API 31+ when the
            // app is no longer in a state that's allowed to start one.
            locationManager.removeUpdates(listener)
            false
        }
    }

    /** Stops recording. Safe to call even if not currently recording. */
    fun stop() {
        if (!_isRecording.value) return
        locationManager.removeUpdates(listener)
        _isRecording.value = false
        _activityType.value = null
        _startedAtEpochMs.value = null
        appContext.stopService(Intent(appContext, LocationRecordingService::class.java))
    }

    private fun onLocation(location: Location) {
        if (location.accuracy < 0f || location.accuracy > MAX_ACCURACY_METERS) return
        val ageMs = System.currentTimeMillis() - location.time
        if (abs(ageMs) > MAX_FIX_AGE_MS) return

        val altitudeMeters = if (location.hasAltitude()) location.altitude else lastKnownAltitudeMeters
        lastKnownAltitudeMeters = altitudeMeters

        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = altitudeMeters,
            recordedAtEpochMs = location.time,
            horizontalAccuracyMeters = location.accuracy.toDouble(),
            verticalAccuracyMeters =
                if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else 0.0
        )
        _routePoints.value = _routePoints.value + point
    }

    private fun hasForegroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Quality filter from the plan's Global Constraints. */
        const val MAX_ACCURACY_METERS = 50.0
        const val MAX_FIX_AGE_MS = 5_000L
        const val MIN_DISTANCE_M = 5f

        /**
         * LocationManager (unlike FusedLocationProviderClient) has no adaptive
         * interval, so this is a deliberate fixed choice rather than a default:
         * 3 seconds. A fast runner (6:00/mi) covers ~5m in a little over a
         * second, so at that pace the 5m distance filter is the binding
         * constraint and 3s never gets a chance to matter; at hiking pace the
         * distance filter fires far less often and 3s keeps the polyline from
         * going long stretches with no update at all, which matters more for
         * following tight switchbacks than shaving points off a straight
         * trail segment.
         */
        const val MIN_TIME_MS = 3_000L

        @Volatile private var instance: LocationTracker? = null

        fun get(context: Context): LocationTracker =
            instance ?: synchronized(this) {
                instance ?: LocationTracker(context).also { instance = it }
            }
    }
}
