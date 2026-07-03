package org.microg.locationtest

import android.location.Location

class MiniLastLocationCapsule {
    var lastFineLocation: Location? = null
        private set
    var lastCoarseLocation: Location? = null
        private set

    fun updateFineLocation(location: Location) {
        lastFineLocation = location
        updateCoarseLocation(location)
    }

    fun updateCoarseLocation(location: Location) {
        lastCoarseLocation = location
    }

    fun getLocationBuggy(): Location? = lastCoarseLocation   // mirrors GmsCore bug
    fun getLocationFixed(): Location? = lastFineLocation      // correct behavior
}
