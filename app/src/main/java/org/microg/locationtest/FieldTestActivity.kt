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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Outdoor field-test page: same bug/fix comparison as MainActivity, plus an
 * outdoor/indoor marker and a record button that writes every fix + bug
 * transition to a plain-text log file, so a walk test can be reviewed later
 * against a separately recorded screen video.
 */
class FieldTestActivity : AppCompatActivity() {

    private lateinit var mapBug: MapView
    private lateinit var mapFix: MapView
    private lateinit var tvBugInfo: TextView
    private lateinit var tvFixInfo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var btnOutdoorToggle: TextView
    private lateinit var btnRecord: TextView

    private lateinit var locationManager: LocationManager

    private val miniLLC = MiniLastLocationCapsule()

    private var lastLoggedHasBug = false
    private var isOutdoor = false
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
            writeLogLine("GPS", location)
        }
        override fun onProviderEnabled(provider: String) { writeRawLine("GPS provider ENABLED") }
        override fun onProviderDisabled(provider: String) { writeRawLine("GPS provider DISABLED") }
    }

    private val networkListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            miniLLC.updateCoarseLocation(location)
            refreshPanels()
            writeLogLine("NETWORK", location)
        }
        override fun onProviderEnabled(provider: String) { writeRawLine("NETWORK provider ENABLED") }
        override fun onProviderDisabled(provider: String) { writeRawLine("NETWORK provider DISABLED") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_field_test)

        mapBug = findViewById(R.id.mapBugField)
        mapFix = findViewById(R.id.mapFixField)
        tvBugInfo = findViewById(R.id.tvBugFieldInfo)
        tvFixInfo = findViewById(R.id.tvFixFieldInfo)
        tvStatus = findViewById(R.id.tvFieldStatus)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)
        btnOutdoorToggle = findViewById(R.id.btnOutdoorToggle)

        setupMap(mapBug)
        setupMap(mapFix)
        linkMapZoom(mapBug, mapFix)

        val codePanelBug = findViewById<View>(R.id.codePanelBugField)
        val codePanelFix = findViewById<View>(R.id.codePanelFixField)
        findViewById<TextView>(R.id.tvCodeBugField).text =
            CodeSnippets.highlightKotlin(CodeSnippets.BUGGY_CODE)
        findViewById<TextView>(R.id.tvCodeFixField).text =
            CodeSnippets.highlightKotlin(CodeSnippets.FIXED_CODE)
        findViewById<TextView>(R.id.btnCodeToggleField).setOnClickListener {
            toggleCodePanel(codePanelBug)
            toggleCodePanel(codePanelFix)
        }

        btnOutdoorToggle.setOnClickListener {
            isOutdoor = !isOutdoor
            btnOutdoorToggle.text = if (isOutdoor) "🌳 Outdoor" else "🏠 Indoor"
            writeRawLine(if (isOutdoor) "--- marked OUTDOOR ---" else "--- marked INDOOR ---")
        }

        btnRecord = findViewById(R.id.btnRecord)
        btnRecord.setOnClickListener { startRecording() }
        findViewById<TextView>(R.id.btnStopRecording).setOnClickListener { stopRecording() }
        findViewById<TextView>(R.id.btnShare).setOnClickListener { shareLastRecording() }

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
            writer.write("format: time,source,provider,lat,lon,accuracy_m,state,bug")
            writer.newLine()
            writer.flush()
            recordWriter = writer
            recordFile = file
            pointsLogged = 0
            isRecording = true
            tvRecordingStatus.text = "● Recording to ${file.name}"
            tvRecordingStatus.setTextColor(Color.parseColor("#F85149"))
            handler.post(blinkRunnable)
            Toast.makeText(this, "Recording started: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "Failed to start recording", e)
            Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            recordWriter?.flush()
            recordWriter?.close()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "Failed to close recording", e)
        }
        isRecording = false
        handler.removeCallbacks(blinkRunnable)
        btnRecord.alpha = 1f
        val name = recordFile?.name ?: "?"
        tvRecordingStatus.text = "✅ Saved: $name  ($pointsLogged points)"
        tvRecordingStatus.setTextColor(Color.parseColor("#8B949E"))
        Toast.makeText(this, "Saved: ${recordFile?.absolutePath}", Toast.LENGTH_LONG).show()
        recordWriter = null
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

    private fun writeRawLine(line: String) {
        if (!isRecording) return
        try {
            recordWriter?.write("${timeFormat.format(Date())} $line")
            recordWriter?.newLine()
            recordWriter?.flush()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "Failed to write log line", e)
        }
    }

    private fun writeLogLine(source: String, location: Location) {
        if (!isRecording) return
        val state = if (isOutdoor) "outdoor" else "indoor"
        val bug = if (lastLoggedHasBug) "BUG" else "-"
        try {
            recordWriter?.write(
                "${timeFormat.format(Date())},$source,${location.provider},${location.latitude}," +
                        "${location.longitude},${location.accuracy},$state,$bug"
            )
            recordWriter?.newLine()
            recordWriter?.flush()
            pointsLogged++
            tvRecordingStatus.text = "● Recording to ${recordFile?.name} — $pointsLogged points logged"
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "Failed to write log line", e)
        }
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

        val hasBug = fix != null && bug != null && bug.provider != "gps" && (fix.provider == "gps")
        lastLoggedHasBug = hasBug

        tvStatus.text = buildString {
            append("bug: ${bug?.accuracy?.toInt() ?: "?"}m (${bug?.provider})  ")
            append("fix: ${fix?.accuracy?.toInt() ?: "?"}m (${fix?.provider})")
            if (hasBug) append("\nBUG CONFIRMED")
        }
        tvStatus.setBackgroundColor(if (hasBug) Color.parseColor("#B71C1C") else Color.parseColor("#212121"))
    }

    /** Toggles [panel] visibility with a GitHub-modal-like expand/collapse animation. */
    private fun toggleCodePanel(panel: View) {
        val root = panel.parent as ViewGroup
        TransitionManager.beginDelayedTransition(root, AutoTransition())
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    override fun onResume() { super.onResume(); mapBug.onResume(); mapFix.onResume() }
    override fun onPause() { super.onPause(); mapBug.onPause(); mapFix.onPause() }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(blinkRunnable)
        locationManager.removeUpdates(gpsListener)
        locationManager.removeUpdates(networkListener)
    }
}
