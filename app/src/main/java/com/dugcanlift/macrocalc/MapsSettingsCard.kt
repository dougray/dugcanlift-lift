package com.dugcanlift.macrocalc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.dugcanlift.liftmap.MapsCard
import com.dugcanlift.liftmap.StreetStore
import com.dugcanlift.macrocalc.data.OutdoorActivity

/**
 * Home's Maps card (`:liftmap`'s [MapsCard]), beside the training settings: this build has no
 * settings screen, so its settings are cards on Home. The streets a run is drawn over are
 * downloaded here, by the person, one area at a time.
 *
 * "Around here" asks for one fix and only when the location permission is already granted: it
 * never prompts, so the row is simply absent for someone who has not allowed location. "Around
 * your last run" is the start of the newest finished route. Neither is sent anywhere by this app;
 * the download itself is the kit's one request.
 */
@Composable
internal fun MapsSettingsCard(outdoor: List<OutdoorActivity>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { runCatching { StreetStore.standard(context) }.getOrNull() } ?: return
    val lastRunStart = remember(outdoor) { lastRunStart(outdoor) }
    MapsCard(
        store = store,
        aroundHere = { currentFix(context) },
        lastRunStart = lastRunStart,
        modifier = modifier,
    )
}

/** The first point of the newest finished activity that recorded a route, as (latitude, longitude). */
internal fun lastRunStart(outdoor: List<OutdoorActivity>): Pair<Double, Double>? =
    outdoor
        .filter { it.endedAtEpochMs != null && it.routePoints.isNotEmpty() }
        .maxByOrNull { it.startedAtEpochMs }
        ?.routePoints?.first()
        ?.let { it.latitude to it.longitude }

/** The system's last known fix, or null when location is not already allowed or none exists. */
@SuppressLint("MissingPermission")
private fun currentFix(context: Context): Pair<Double, Double>? {
    val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (!allowed) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val fix = runCatching {
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    }.getOrNull() ?: return null
    return fix.latitude to fix.longitude
}
