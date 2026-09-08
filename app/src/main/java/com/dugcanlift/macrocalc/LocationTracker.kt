package com.dugcanlift.macrocalc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dugcanlift.macrocalc.data.RoutePoint
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

    private val listener = LocationListener { location -> onLocation(location) }

    /**
     * Starts recording: registers for location updates and promotes the
     * process via [LocationRecordingService]. Returns `false` without side
     * effects if foreground location permission isn't granted yet, or if no
     * provider is enabled, or if the OS refuses the foreground-service start
     * (e.g. restrictions on starting a foreground service while backgrounded).
     * Already-recording calls are a no-op success.
     */
    @SuppressLint("MissingPermission") // checked explicitly via hasForegroundLocationPermission()
    fun start(): Boolean {
        if (_isRecording.value) return true
        if (!hasForegroundLocationPermission()) return false

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: return false

        _routePoints.value = emptyList()

        return try {
            locationManager.requestLocationUpdates(
                provider,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper()
            )
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, LocationRecordingService::class.java)
            )
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
        appContext.stopService(Intent(appContext, LocationRecordingService::class.java))
    }

    private fun onLocation(location: Location) {
        if (location.accuracy < 0f || location.accuracy > MAX_ACCURACY_METERS) return
        val ageMs = System.currentTimeMillis() - location.time
        if (ageMs > MAX_FIX_AGE_MS) return

        val point = RoutePoint(
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
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
