package org.microg.locationtest

import android.location.Location
import android.os.SystemClock
import androidx.core.location.LocationCompat
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MiniLastLocationCapsule {
    var lastFineLocation: Location? = null
        private set
    var lastCoarseLocation: Location? = null
        private set

    // Mirrors GmsCore's privacy-motivated coarsened-timestamp variants; like the real
    // lastFineLocation, these are kept up to date but never read by the getters below.
    private var lastFineLocationTimeCoarsed: Location? = null
    private var lastCoarseLocationTimeCoarsed: Location? = null

    private val Location.elapsedMillis: Long
        get() = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos)

    fun updateFineLocation(location: Location) {
        location.elapsedRealtimeNanos = min(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
        location.time = min(location.time, System.currentTimeMillis())
        lastFineLocation = newest(lastFineLocation, location)
        lastFineLocationTimeCoarsed = newest(lastFineLocationTimeCoarsed, location, TIME_COARSE_CLIFF)
        updateCoarseLocation(location)
    }

    fun updateCoarseLocation(location: Location) {
        location.elapsedRealtimeNanos = min(location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
        location.time = min(location.time, System.currentTimeMillis())
        val previous = lastCoarseLocation
        if (previous != null && previous.elapsedMillis + EXTENSION_CLIFF > location.elapsedMillis) {
            if (!location.hasSpeed()) {
                location.speed = previous.distanceTo(location) / ((location.elapsedMillis - previous.elapsedMillis) / 1000)
                LocationCompat.setSpeedAccuracyMetersPerSecond(location, location.speed)
            }
            if (!location.hasBearing() && location.speed > 0.5f) {
                location.bearing = previous.bearingTo(location)
                LocationCompat.setBearingAccuracyDegrees(location, 180.0f)
            }
        }
        lastCoarseLocation = newest(lastCoarseLocation, location)
        lastCoarseLocationTimeCoarsed = newest(lastCoarseLocationTimeCoarsed, location, TIME_COARSE_CLIFF)
    }

    private fun newest(oldLocation: Location?, newLocation: Location, cliff: Long = 0): Location {
        if (oldLocation == null) return newLocation
        val old = Location(oldLocation)
        old.elapsedRealtimeNanos = min(old.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
        old.time = min(old.time, System.currentTimeMillis())
        if (newLocation.elapsedRealtimeNanos >= old.elapsedRealtimeNanos + TimeUnit.MILLISECONDS.toNanos(cliff)) return newLocation
        return old
    }

    private fun withAgeCliff(location: Location?, maxUpdateAgeMillis: Long): Location? {
        location ?: return null
        val elapsedRealtimeDiff = SystemClock.elapsedRealtime() - location.elapsedMillis
        if (elapsedRealtimeDiff > maxUpdateAgeMillis) return null
        return location
    }

    fun getLocationBuggy(maxUpdateAgeMillis: Long = Long.MAX_VALUE): Location? =
        withAgeCliff(lastCoarseLocation, maxUpdateAgeMillis)   // mirrors GmsCore bug

    fun getLocationFixed(maxUpdateAgeMillis: Long = Long.MAX_VALUE): Location? =
        withAgeCliff(lastFineLocation, maxUpdateAgeMillis)   // correct behavior

    companion object {
        private const val TIME_COARSE_CLIFF = 60_000L
        private const val EXTENSION_CLIFF = 30_000L
    }
}
