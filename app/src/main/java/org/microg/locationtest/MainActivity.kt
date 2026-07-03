package org.microg.locationtest

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class MainActivity : AppCompatActivity() {

    private lateinit var mapBug: MapView
    private lateinit var mapFix: MapView
    private lateinit var tvBugInfo: TextView
    private lateinit var tvFixInfo: TextView
    private lateinit var tvStatus: TextView

    private lateinit var locationManager: LocationManager

    private val miniLLC = MiniLastLocationCapsule()

    private var syncingZoom = false

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshPanels()
            handler.postDelayed(this, 500)
        }
    }

    private val gpsListener = LocationListener { location ->
        miniLLC.updateFineLocation(location)
        refreshPanels()
    }

    private val networkListener = LocationListener { location ->
        miniLLC.updateCoarseLocation(location)
        refreshPanels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        mapBug = findViewById(R.id.mapCoarse)
        mapFix = findViewById(R.id.mapFine)
        tvBugInfo = findViewById(R.id.tvCoarseInfo)
        tvFixInfo = findViewById(R.id.tvFineInfo)
        tvStatus = findViewById(R.id.tvStatus)

        setupMap(mapBug)
        setupMap(mapFix)
        linkZoom(mapBug, mapFix)
        linkZoom(mapFix, mapBug)

        locationManager = getSystemService<LocationManager>()!!

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1
            )
        } else {
            startUpdates()
        }
    }

    private fun setupMap(map: MapView) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
    }

    /** Mirrors zoom level from [source] to [target] so both panels stay at the same scale. */
    private fun linkZoom(source: MapView, target: MapView) {
        source.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean = false

            override fun onZoom(event: ZoomEvent?): Boolean {
                if (syncingZoom) return false
                syncingZoom = true
                target.controller.setZoom(source.zoomLevelDouble)
                syncingZoom = false
                return false
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startUpdates()
        else tvStatus.text = "Permission denied"
    }

    @Suppress("MissingPermission")
    private fun startUpdates() {
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener)
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, networkListener)
        handler.post(tickRunnable)
    }

    private fun refreshPanels() {
        val bugLocation = miniLLC.getLocationBuggy()
        val fixLocation = miniLLC.getLocationFixed()

        if (bugLocation != null) {
            updateMap(mapBug, bugLocation, "#220066FF", "#990066FF")
            updateInfo(tvBugInfo, bugLocation)
        }
        if (fixLocation != null) {
            updateMap(mapFix, fixLocation, "#2200AA00", "#9900AA00")
            updateInfo(tvFixInfo, fixLocation)
        }

        updateStatus(bugLocation, fixLocation)
    }

    private fun updateMap(map: MapView, loc: Location, fillHex: String, strokeHex: String) {
        val center = GeoPoint(loc.latitude, loc.longitude)
        map.overlays.clear()

        if (loc.accuracy > 0f) {
            val circle = Polygon(map).apply {
                points = Polygon.pointsAsCircle(center, loc.accuracy.toDouble())
                fillColor = Color.parseColor(fillHex)
                strokeColor = Color.parseColor(strokeHex)
                strokeWidth = 2f
            }
            map.overlays.add(circle)
        }

        val marker = Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            title = "${loc.provider}  ${loc.accuracy.toInt()} m"
        }
        map.overlays.add(marker)

        map.controller.setCenter(center)
        if (map.zoomLevelDouble < 13.0) map.controller.setZoom(15.0)
        map.invalidate()
    }

    private fun updateInfo(tv: TextView, loc: Location) {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        tv.text = "${String.format("%.6f", loc.latitude)}, ${String.format("%.6f", loc.longitude)}" +
                "  |  ${loc.accuracy.toInt()} m  |  provider: ${loc.provider}  |  ${ageMs / 1000.0}s ago"
    }

    private fun updateStatus(bug: Location?, fix: Location?) {
        if (bug == null && fix == null) {
            tvStatus.text = "Waiting for location... (go outdoors for GPS)"
            tvStatus.setBackgroundColor(Color.parseColor("#212121"))
            return
        }

        val bugAge = bug?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000.0 }
        val fixAge = fix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000.0 }

        val hasBug = fix != null && bug != null && bug.provider != "gps" && (fix.provider == "gps")

        tvStatus.text = buildString {
            append("bug: ${bug?.accuracy?.toInt() ?: "?"}m (${bug?.provider})  ")
            append("fix: ${fix?.accuracy?.toInt() ?: "?"}m (${fix?.provider})\n")
            if (hasBug) {
                append("BUG CONFIRMED: fix panel got GPS (${fix!!.accuracy.toInt()}m) but bug panel still shows network")
            } else if (fix?.provider == "gps" && bug?.provider == "gps") {
                append("Both have GPS fix — bug not visible yet (network fix hasn't arrived)")
            } else {
                append("%.1fs since bug update  |  %.1fs since fix update".format(bugAge ?: 0.0, fixAge ?: 0.0))
            }
        }
        tvStatus.setBackgroundColor(if (hasBug) Color.parseColor("#B71C1C") else Color.parseColor("#212121"))
    }

    override fun onResume() { super.onResume(); mapBug.onResume(); mapFix.onResume() }
    override fun onPause() { super.onPause(); mapBug.onPause(); mapFix.onPause() }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(networkListener)
    }
}
