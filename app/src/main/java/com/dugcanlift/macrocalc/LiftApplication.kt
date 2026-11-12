package com.dugcanlift.macrocalc

import android.app.Application
import com.dugcanlift.liftmap.MapNetworkGuard

/**
 * Tells MapLibre it is offline before any map is made, so a map only ever reads the streets the
 * person downloaded. `RouteMap` installs the guard too; doing it here means no screen can reach
 * MapLibre first. Calling it again does nothing.
 */
class LiftApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapNetworkGuard.install(this)
    }
}
