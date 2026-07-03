package org.microg.locationtest

import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.MapView

/** Mirrors zoom level between [a] and [b] so both panels stay at the same scale. */
fun linkMapZoom(a: MapView, b: MapView) {
    var syncing = false

    fun listenerFor(source: MapView, target: MapView) = object : MapListener {
        override fun onScroll(event: ScrollEvent?): Boolean = false

        override fun onZoom(event: ZoomEvent?): Boolean {
            if (syncing) return false
            syncing = true
            target.controller.setZoom(source.zoomLevelDouble)
            syncing = false
            return false
        }
    }

    a.addMapListener(listenerFor(a, b))
    b.addMapListener(listenerFor(b, a))
}
