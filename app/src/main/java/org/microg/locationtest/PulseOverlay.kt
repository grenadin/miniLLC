package org.microg.locationtest

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.animation.LinearInterpolator
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class PulseOverlay(private val strokeColor: Int) : Overlay() {

    private var center: GeoPoint? = null
    private var accuracyMeters: Float = 0f
    private var pulseProgress: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { pulseProgress = it.animatedValue as Float }
    }

    fun update(newCenter: GeoPoint, accuracy: Float, mapView: MapView) {
        center = newCenter
        accuracyMeters = accuracy
        if (!animator.isRunning) animator.start()
        mapView.invalidate()
    }

    fun stop() {
        animator.cancel()
        center = null
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val c = center ?: return
        if (accuracyMeters <= 0f) return

        val projection = mapView.projection
        val screenCenter = projection.toPixels(c, null)

        // convert accuracy meters to pixels at current zoom
        val latRad = Math.toRadians(c.latitude)
        val metersPerPx = 156543.03392 * Math.cos(latRad) / Math.pow(2.0, mapView.zoomLevelDouble)
        val accuracyPx = (accuracyMeters / metersPerPx).toFloat()

        // expanding ring: starts at center dot, grows to accuracy radius, fades out
        val radius = accuracyPx * pulseProgress
        val alpha = ((1f - pulseProgress) * 180).toInt()

        paint.color = Color.argb(alpha,
            Color.red(strokeColor),
            Color.green(strokeColor),
            Color.blue(strokeColor))
        canvas.drawCircle(screenCenter.x.toFloat(), screenCenter.y.toFloat(), radius, paint)

        mapView.postInvalidateDelayed(16)
    }
}
