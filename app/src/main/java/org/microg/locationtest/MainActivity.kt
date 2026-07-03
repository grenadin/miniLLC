package org.microg.locationtest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var mapBug: MapView
    private lateinit var mapFix: MapView
    private lateinit var scaleBarBug: ScaleBarOverlay
    private lateinit var scaleBarFix: ScaleBarOverlay
    private lateinit var pulseBug: PulseOverlay
    private lateinit var pulseFix: PulseOverlay
    private lateinit var tvBugInfo: TextView
    private lateinit var tvFixInfo: TextView
    private lateinit var tvZoomLevelBug: TextView
    private lateinit var tvZoomLevelFix: TextView
    private lateinit var tvStatus: TextView

    private lateinit var pageMap: View
    private lateinit var pageLog: View
    private lateinit var btnTabMap: TextView
    private lateinit var btnTabLog: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var btnRecord: TextView
    private lateinit var btnShare: View
    private lateinit var btnDelete: TextView
    private lateinit var tvLogViewer: TextView
    private lateinit var scrollLogViewer: ScrollView

    private lateinit var locationManager: LocationManager

    private val miniLLC = MiniLastLocationCapsule()

    private var lastLoggedHasBug = false
    private var isRecording = false
    private var recordWriter: BufferedWriter? = null
    private var recordFile: File? = null
    private var pointsLogged = 0

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshPanels()
            handler.postDelayed(this, 500)
        }
    }

    private var blinkOn = false
    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            btnRecord.alpha = if (blinkOn) 1f else 0.35f
            if (isRecording) handler.postDelayed(this, 500)
        }
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            miniLLC.updateFineLocation(location)
            refreshPanels()
            writeLocationPair()
        }
        override fun onProviderEnabled(provider: String) {
            writeRawLine("GPS provider ENABLED")
        }
        override fun onProviderDisabled(provider: String) {
            writeRawLine("GPS provider DISABLED (signal lost / turned off)")
        }
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            miniLLC.updateCoarseLocation(location)
            refreshPanels()
            writeLocationPair()
        }
        override fun onProviderEnabled(provider: String) {
            writeRawLine("NETWORK provider ENABLED")
        }
        override fun onProviderDisabled(provider: String) {
            writeRawLine("NETWORK provider DISABLED")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        mapBug = findViewById(R.id.mapCoarse)
        mapFix = findViewById(R.id.mapFine)
        tvBugInfo = findViewById(R.id.tvCoarseInfo)
        tvFixInfo = findViewById(R.id.tvFineInfo)
        tvZoomLevelBug = findViewById(R.id.tvZoomLevelBug)
        tvZoomLevelFix = findViewById(R.id.tvZoomLevelFix)
        tvStatus = findViewById(R.id.tvStatus)

        pageMap = findViewById(R.id.pageMap)
        pageLog = findViewById(R.id.pageLog)
        btnTabMap = findViewById(R.id.btnTabMap)
        btnTabLog = findViewById(R.id.btnTabLog)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnShare = findViewById(R.id.btnShare)
        btnDelete = findViewById(R.id.btnDelete)
        tvLogViewer = findViewById(R.id.tvLogViewer)
        scrollLogViewer = findViewById(R.id.scrollLogViewer)

        scaleBarBug = setupMap(mapBug)
        scaleBarFix = setupMap(mapFix)
        pulseBug = PulseOverlay(Color.parseColor("#E53935"))
        pulseFix = PulseOverlay(Color.parseColor("#43A047"))
        mapBug.overlays.add(pulseBug)
        mapFix.overlays.add(pulseFix)
        linkMapZoom(mapBug, mapFix) { zoom ->
            val label = zoom.toInt().toString()
            tvZoomLevelBug.text = label
            tvZoomLevelFix.text = label
        }

        fun zoomBoth(delta: Double) {
            val next = mapBug.zoomLevelDouble + delta
            mapBug.controller.setZoom(next)
            mapFix.controller.setZoom(next)
            val label = next.toInt().toString()
            tvZoomLevelBug.text = label
            tvZoomLevelFix.text = label
        }
        findViewById<TextView>(R.id.btnZoomInBug).setOnClickListener { zoomBoth(+1.0) }
        findViewById<TextView>(R.id.btnZoomOutBug).setOnClickListener { zoomBoth(-1.0) }
        findViewById<TextView>(R.id.btnZoomInFix).setOnClickListener { zoomBoth(+1.0) }
        findViewById<TextView>(R.id.btnZoomOutFix).setOnClickListener { zoomBoth(-1.0) }

        val btnCodeBug = findViewById<TextView>(R.id.btnCodeBug)
        val codePanelBug = findViewById<View>(R.id.codePanelBug)
        val tvCodeBug = findViewById<TextView>(R.id.tvCodeBug)
        tvCodeBug.text = CodeSnippets.highlightKotlin(CodeSnippets.BUGGY_CODE)

        val codePanelFix = findViewById<View>(R.id.codePanelFix)
        val tvCodeFix = findViewById<TextView>(R.id.tvCodeFix)
        tvCodeFix.text = CodeSnippets.highlightKotlin(CodeSnippets.FIXED_CODE)

        btnCodeBug.setOnClickListener {
            toggleCodePanel(codePanelBug)
            toggleCodePanel(codePanelFix)
        }

        btnTabMap.setOnClickListener { showPage(isMap = true) }
        btnTabLog.setOnClickListener { showPage(isMap = false) }

        btnRecord.setOnClickListener { startRecording() }
        findViewById<View>(R.id.btnStopRecording).setOnClickListener { stopRecording() }
        btnShare.setOnClickListener { shareLastRecording() }
        btnDelete.setOnClickListener { deleteLastRecording() }

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

    /** Switches between the map-comparison page and the live log page; recording keeps running either way. */
    private fun showPage(isMap: Boolean) {
        pageMap.visibility = if (isMap) View.VISIBLE else View.GONE
        pageLog.visibility = if (isMap) View.GONE else View.VISIBLE
        btnTabMap.setTextColor(Color.parseColor(if (isMap) "#58A6FF" else "#C9D1D9"))
        btnTabLog.setTextColor(Color.parseColor(if (isMap) "#C9D1D9" else "#58A6FF"))
    }

    private fun setupMap(map: MapView): ScaleBarOverlay {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)
        val scaleBar = ScaleBarOverlay(map).apply {
            setAlignBottom(false)
        }
        map.overlays.add(scaleBar)
        return scaleBar
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

    private fun startRecording() {
        if (isRecording) return
        val fileName = "fieldtest_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        val file = File(getExternalFilesDir(null), fileName)
        try {
            val writer = BufferedWriter(FileWriter(file))
            writer.write("miniLLC field test log — device=${android.os.Build.MODEL} start=${timeFormat.format(Date())}")
            writer.newLine()
            writer.write("format: each update is a Bug Panel / Fix Panel line pair, separated by a dashed line")
            writer.newLine()
            writer.flush()
            recordWriter = writer
            recordFile = file
            pointsLogged = 0
            isRecording = true
            btnShare.visibility = View.GONE
            btnDelete.visibility = View.GONE
            tvLogViewer.text = ""
            tvRecordingStatus.text = "● Recording to ${file.name}"
            tvRecordingStatus.setTextColor(Color.parseColor("#F85149"))
            handler.post(blinkRunnable)
            Toast.makeText(this, "Recording started: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recordWriter?.flush()
            recordWriter?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close recording", e)
        }
        isRecording = false
        handler.removeCallbacks(blinkRunnable)
        btnRecord.alpha = 1f
        val name = recordFile?.name ?: "?"
        tvRecordingStatus.text = "✅ Saved: $name  ($pointsLogged points)"
        tvRecordingStatus.setTextColor(Color.parseColor("#8B949E"))
        Toast.makeText(this, "Saved: ${recordFile?.absolutePath}", Toast.LENGTH_LONG).show()
        recordWriter = null
        if (recordFile != null && pointsLogged > 0) {
            btnShare.visibility = View.VISIBLE
            btnDelete.visibility = View.VISIBLE
        }
        // recordFile is kept (not nulled) so the Share button can still send the last recording.
    }

    private fun shareLastRecording() {
        val file = recordFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No recording to share yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share field test log"))
    }

    private fun deleteLastRecording() {
        val file = recordFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "No recording to delete", Toast.LENGTH_SHORT).show()
            return
        }
        file.delete()
        recordFile = null
        btnShare.visibility = View.GONE
        btnDelete.visibility = View.GONE
        tvRecordingStatus.text = "Not recording"
        tvRecordingStatus.setTextColor(Color.parseColor("#8B949E"))
        tvLogViewer.text = "No log entries yet — press REC to start."
        Toast.makeText(this, "Deleted: ${file.name}", Toast.LENGTH_SHORT).show()
    }

    private fun writeRawLine(line: String) {
        if (!isRecording) return
        val text = "${timeFormat.format(Date())} $line"
        try {
            recordWriter?.write(text)
            recordWriter?.newLine()
            recordWriter?.flush()
            appendToLogViewer(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log line", e)
        }
    }

    /** Formats one panel's current location as "Bug Panel [Provider:...] [Position:...] [meter:...] [Date and Time]". */
    private fun formatPanelLine(label: String, loc: Location?): String {
        if (loc == null) return "$label [Provider:-] [Position:-] [meter:-] [${timeFormat.format(Date())}]"
        return "$label [Provider:${loc.provider}] [Position:${loc.latitude},${loc.longitude}] " +
                "[meter:${loc.accuracy}] [${timeFormat.format(Date())}]"
    }

    /** Writes the Bug/Fix panels' current state as a matched pair, so they're easy to compare in the log. */
    private fun writeLocationPair() {
        if (!isRecording) return
        val bug = miniLLC.getLocationBuggy()
        val fix = miniLLC.getLocationFixed()
        val block = "${formatPanelLine("Bug Panel", bug)}\n${formatPanelLine("Fix Panel", fix)}\n${"-".repeat(80)}"
        try {
            recordWriter?.write(block)
            recordWriter?.newLine()
            recordWriter?.flush()
            pointsLogged++
            tvRecordingStatus.text = "● Recording to ${recordFile?.name} — $pointsLogged updates logged"
            appendToLogViewer(block)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log line", e)
        }
    }

    /** Appends [line] to the live log page and auto-scrolls it into view. */
    private fun appendToLogViewer(line: String) {
        tvLogViewer.append("\n$line")
        scrollLogViewer.post { scrollLogViewer.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshPanels() {
        val bugLocation = miniLLC.getLocationBuggy()
        val fixLocation = miniLLC.getLocationFixed()

        if (bugLocation != null) {
            updateMap(mapBug, bugLocation, "#1AE53935", "#80E53935", R.drawable.ic_marker_bug)
            updateInfo(tvBugInfo, bugLocation)
        }
        if (fixLocation != null) {
            updateMap(mapFix, fixLocation, "#1A43A047", "#8043A047", R.drawable.ic_marker_fix)
            updateInfo(tvFixInfo, fixLocation)
        }


        updateStatus(bugLocation, fixLocation)
    }

    private fun updateMap(map: MapView, loc: Location, fillHex: String, strokeHex: String, markerRes: Int) {
        val center = GeoPoint(loc.latitude, loc.longitude)
        val scaleBar = if (map === mapBug) scaleBarBug else scaleBarFix
        val pulse = if (map === mapBug) pulseBug else pulseFix
        map.overlays.clear()
        map.overlays.add(scaleBar)
        map.overlays.add(pulse)

        if (loc.accuracy > 0f) {
            val circle = Polygon(map).apply {
                points = Polygon.pointsAsCircle(center, loc.accuracy.toDouble())
                fillColor = Color.parseColor(fillHex)
                strokeColor = Color.parseColor(strokeHex)
                strokeWidth = 1.5f
            }
            map.overlays.add(circle)
        }

        val icon = ContextCompat.getDrawable(this, markerRes)
        val marker = Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setIcon(icon)
            title = "${loc.provider}  ${loc.accuracy.toInt()} m"
        }
        map.overlays.add(marker)

        map.controller.setCenter(center)

        if (loc.provider != "gps") {
            pulse.update(center, loc.accuracy, map)
        } else {
            pulse.stop()
        }

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
        lastLoggedHasBug = hasBug

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
        if (isRecording) stopRecording()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(blinkRunnable)
        pulseBug.stop()
        pulseFix.stop()
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(networkListener)
    }

    /** Toggles [panel] visibility with a GitHub-modal-like expand/collapse animation. */
    private fun toggleCodePanel(panel: View) {
        val root = panel.parent as ViewGroup
        TransitionManager.beginDelayedTransition(root, AutoTransition())
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    companion object {
        const val TAG = "miniLLC"
    }
}
