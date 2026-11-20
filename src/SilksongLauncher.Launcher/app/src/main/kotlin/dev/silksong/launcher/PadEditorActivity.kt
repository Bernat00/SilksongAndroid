package dev.silksong.launcher

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The on-screen pad's layout editor.
 *
 * Drag a key to move it, pinch it to scale it, drag the ring to move the
 * stick, drag inside the zone or pinch it to move/resize the zone. Save writes
 * `pad-layout.json`, which the game reads at its next launch -- no rebuild, no
 * conversion, because the pad is compiled into the image and this file is the
 * only part of it that is not.
 *
 * Deliberately a launcher screen rather than drag-in-game: the whole layout is
 * visible at once, nothing is on top of it, and a mistake here cannot break the
 * game's touch handling. Everything it enforces (no overlapping keys, sane
 * ranges) is enforced again by the game when it reads the file.
 */
class PadEditorActivity : Activity() {

    private lateinit var canvas: PadCanvas
    private lateinit var status: TextView
    private lateinit var pinButton: Button
    private lateinit var chromeTop: View
    private lateinit var chromeBottom: View
    private lateinit var peek: TextView

    private companion object {
        /** Smallest stick zone, as a fraction of the screen. */
        const val MIN_SPAN = 0.05f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The canvas has to be the game's canvas: same orientation, same area,
        // nothing shaving a strip off the bottom. So: landscape, fullscreen,
        // and the controls float over the layout instead of sitting beside it.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0D0A0B"))
        }

        canvas = PadCanvas()
        root.addView(canvas, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "On-screen pad"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        top.addView(TextView(this).apply {
            text = "Drag to move · pinch to scale · tap the ring or the zone to select it. " +
                "The game picks this up the next time it starts."
            setTextColor(Color.parseColor("#B5A9AC"))
            textSize = 11f
        })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        status = TextView(this).apply {
            setTextColor(Color.parseColor("#B5A9AC"))
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }
        bottom.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pinButton = button("Stick: floating") { togglePin() }
        row.addView(pinButton, weight())
        row.addView(button("Reset") { reset() }, weight())
        row.addView(button("Cancel") { finish() }, weight())
        row.addView(button("Save") { save() }, weight())
        row.addView(button("Hide") { setChromeVisible(false) }, weight())
        bottom.addView(row)

        root.addView(top, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP))
        root.addView(bottom, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // A small way back when everything else is out of the way.
        peek = TextView(this).apply {
            text = "▾"
            setTextColor(Color.parseColor("#B5A9AC"))
            textSize = 16f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { setChromeVisible(true) }
            visibility = View.GONE
        }
        root.addView(peek, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { topMargin = dp(8); marginEnd = dp(8) })

        chromeTop = top
        chromeBottom = bottom

        setContentView(root)
        canvas.load()
        updatePin()
    }

    /** The top and bottom strips, hidden together so the whole screen is visible. */
    private fun setChromeVisible(visible: Boolean) {
        chromeTop.visibility = if (visible) View.VISIBLE else View.GONE
        chromeBottom.visibility = if (visible) View.VISIBLE else View.GONE
        peek.visibility = if (visible) View.GONE else View.VISIBLE
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        .apply { marginEnd = dp(6) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), dp(10), dp(6), dp(10))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7D3341"))
        setTextColor(Color.WHITE)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun togglePin() {
        val stick = canvas.layout.stick
        stick.mode = if (stick.mode == "pinned") "floating" else "pinned"
        updatePin()
        canvas.invalidate()
    }

    private fun updatePin() {
        pinButton.text = if (canvas.layout.stick.mode == "pinned") "Stick: pinned" else "Stick: floating"
    }

    private fun reset() {
        canvas.layout = PadLayout.defaults(canvas.width, canvas.height)
        updatePin()
        canvas.deselect()
        canvas.invalidate()
        status.text = "Reset to the built-in layout. Save to keep it."
    }

    private fun save() {
        val clash = canvas.overlappingKeys()
        if (clash != null) {
            status.text = "Those two overlap: $clash. Move one before saving."
            return
        }
        try {
            PadLayout.save(this, canvas.layout)
            status.text = "Saved. The game uses it next time it starts."
            LauncherLog.log("pad editor: saved ${canvas.layout.keys.size} keys")
        } catch (t: Throwable) {
            status.text = "Could not save: ${t.message}"
            LauncherLog.log("pad editor: save failed", t)
        }
    }

    /**
     * The pad, drawn the way the game will draw it and hit the way the game
     * will hit it: one rect per item serving both, so what is touched is what
     * is shown.
     */
    inner class PadCanvas : View(this@PadEditorActivity) {

        var layout: PadLayout = PadLayout.defaults(1, 1)
        private var selectedKey = -1
        private var selectedStick = false
        private var selectedZone = false
        private var scaling = false
        private var dragCorner = -1
        private var startDistance = 0f
        private var startValue = 0f
        private var lastX = 0f
        private var lastY = 0f

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
        }

        fun load() {
            // Called from onCreate before the first layout: a zero-sized view
            // cannot produce a layout, so wait for one rather than computing
            // with 0 (see PadLayout.defaults).
            if (width <= 0 || height <= 0) {
                post { load() }
                return
            }
            layout = PadLayout.load(this@PadEditorActivity, width, height)
            invalidate()
        }

        fun deselect() {
            selectedKey = -1
            selectedStick = false
            selectedZone = false
        }

        private fun vmin() = minOf(width, height).toFloat().coerceAtLeast(1f)

        private fun keyRect(k: PadLayout.Key): RectF {
            val cx = k.x * width
            val cy = k.y * height
            val w = k.w * vmin() * k.scale
            val h = k.h * vmin() * k.scale
            return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        }

        private fun zoneRect(): RectF {
            val s = layout.stick
            return RectF(s.x0 * width, s.y0 * height, s.x1 * width, s.y1 * height)
        }

        private fun ringCentre() =
            android.graphics.PointF(layout.stick.cx * width, layout.stick.cy * height)

        /** The four corner handles of the stick zone: independently x and y. */
        private fun cornerRects(): List<RectF> {
            val z = zoneRect()
            val r = 9f * resources.displayMetrics.density
            return listOf(
                RectF(z.left - r, z.top - r, z.left + r, z.top + r),
                RectF(z.right - r, z.top - r, z.right + r, z.top + r),
                RectF(z.left - r, z.bottom - r, z.left + r, z.bottom + r),
                RectF(z.right - r, z.bottom - r, z.right + r, z.bottom + r),
            )
        }

        fun overlappingKeys(): String? = layout.firstOverlap(width, height)

        override fun onDraw(g: Canvas) {
            super.onDraw(g)
            val s = layout.stick
            val zone = zoneRect()
            fill.color = Color.parseColor("#1F7D3341")
            g.drawRect(zone, fill)
            stroke.color = if (selectedZone) Color.WHITE else Color.parseColor("#7D3341")
            g.drawRect(zone, stroke)
            // Corner handles: x and y are separate spans on a phone screen, so
            // resizing them together is the one thing a rectangle cannot mean.
            handlePaint.color = if (selectedZone) Color.WHITE else Color.parseColor("#7D3341")
            for (h in cornerRects()) g.drawCircle(h.centerX(), h.centerY(), h.width() / 2f, handlePaint)

            val centre = ringCentre()
            val radius = s.r * vmin()
            fill.color = Color.parseColor("#33000000")
            g.drawCircle(centre.x, centre.y, radius, fill)
            stroke.color = if (selectedStick) Color.WHITE else Color.parseColor("#B5A9AC")
            g.drawCircle(centre.x, centre.y, radius, stroke)

            text.textSize = resources.displayMetrics.density * 13f
            for ((index, k) in layout.keys.withIndex()) {
                val r = keyRect(k)
                fill.color = Color.parseColor("#55000000")
                if (k.shape == "pill") {
                    g.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
                } else {
                    g.drawOval(r, fill)
                }
                stroke.color = if (index == selectedKey) Color.WHITE else Color.parseColor("#B5A9AC")
                if (k.shape == "pill") {
                    g.drawRoundRect(r, r.height() / 2f, r.height() / 2f, stroke)
                } else {
                    g.drawOval(r, stroke)
                }
                g.drawText(k.label, r.centerX(), r.centerY() + text.textSize / 3f, text)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(event.x, event.y)
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) beginScale(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaling && event.pointerCount >= 2) applyScale(event) else drag(event.x, event.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scaling = false
                    dragCorner = -1
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    scaling = false
                    dragCorner = -1
                }
            }
            return true
        }

        private fun begin(x: Float, y: Float) {
            lastX = x
            lastY = y
            scaling = false
            // Keys first, then the ring, then the zone: the drawing order
            // bottom-up, so the thing on top is the thing picked.
            for (index in layout.keys.indices.reversed()) {
                if (keyRect(layout.keys[index]).contains(x, y)) {
                    deselect()
                    selectedKey = index
                    status.text = "${layout.keys[index].label}: drag to move, pinch to scale " +
                        "(${layout.keys[index].target})"
                    invalidate()
                    return
                }
            }
            val centre = ringCentre()
            val radius = layout.stick.r * vmin()
            val dx = x - centre.x
            val dy = y - centre.y
            if (dx * dx + dy * dy <= radius * radius) {
                deselect()
                selectedStick = true
                status.text = "Stick ring: drag to move, pinch its size"
                invalidate()
                return
            }
            if (zoneRect().contains(x, y) || cornerRects().any { it.contains(x, y) }) {
                deselect()
                selectedZone = true
                // Which corner, if any: dragging a handle resizes one axis at
                // a time (x and y are different spans and a phone screen is
                // not square); the body itself moves the whole rectangle.
                dragCorner = cornerRects().indexOfFirst { it.contains(x, y) }
                status.text = if (dragCorner >= 0) {
                    "Stick zone corner ${dragCorner + 1}: drag to resize x and y separately"
                } else {
                    "Stick zone: drag to move, pinch to resize, or grab a corner"
                }
                invalidate()
                return
            }
            deselect()
            status.text = "Tap a key, the ring or the zone to select it."
            invalidate()
        }

        private fun drag(x: Float, y: Float) {
            val dx = (x - lastX) / width
            val dy = (y - lastY) / height
            lastX = x
            lastY = y
            if (selectedKey >= 0) {
                val k = layout.keys[selectedKey]
                k.x = (k.x + dx).coerceIn(0.02f, 0.98f)
                k.y = (k.y + dy).coerceIn(0.02f, 0.98f)
            } else if (selectedStick) {
                val s = layout.stick
                s.cx = (s.cx + dx).coerceIn(0f, 1f)
                s.cy = (s.cy + dy).coerceIn(0f, 1f)
            } else if (selectedZone) {
                val s = layout.stick
                if (dragCorner >= 0) {
                    val nx = (x / width).coerceIn(0f, 1f)
                    val ny = (y / height).coerceIn(0f, 1f)
                    when (dragCorner) {
                        0 -> {
                            s.x0 = nx.coerceAtMost(s.x1 - MIN_SPAN)
                            s.y0 = ny.coerceAtMost(s.y1 - MIN_SPAN)
                        }
                        1 -> {
                            s.x1 = nx.coerceAtLeast(s.x0 + MIN_SPAN)
                            s.y0 = ny.coerceAtMost(s.y1 - MIN_SPAN)
                        }
                        2 -> {
                            s.x0 = nx.coerceAtMost(s.x1 - MIN_SPAN)
                            s.y1 = ny.coerceAtLeast(s.y0 + MIN_SPAN)
                        }
                        else -> {
                            s.x1 = nx.coerceAtLeast(s.x0 + MIN_SPAN)
                            s.y1 = ny.coerceAtLeast(s.y0 + MIN_SPAN)
                        }
                    }
                } else {
                    val w = s.x1 - s.x0
                    val h = s.y1 - s.y0
                    s.x0 = (s.x0 + dx).coerceIn(0f, 1f - w)
                    s.y0 = (s.y0 + dy).coerceIn(0f, 1f - h)
                    s.x1 = s.x0 + w
                    s.y1 = s.y0 + h
                }
            } else {
                return
            }
            invalidate()
        }

        private fun beginScale(event: MotionEvent) {
            scaling = true
            startDistance = distance(event)
            startValue = when {
                selectedKey >= 0 -> layout.keys[selectedKey].scale
                selectedStick -> layout.stick.r
                selectedZone -> layout.stick.x1 - layout.stick.x0
                else -> 0f
            }
        }

        private fun applyScale(event: MotionEvent) {
            if (startDistance <= 0f || startValue <= 0f) return
            val factor = (distance(event) / startDistance).coerceIn(0.5f, 2f)
            when {
                selectedKey >= 0 -> layout.keys[selectedKey].scale = (startValue * factor).coerceIn(0.5f, 2f)
                selectedStick -> layout.stick.r = (startValue * factor).coerceIn(0.05f, 0.30f)
                selectedZone -> {
                    val s = layout.stick
                    val cx = (s.x0 + s.x1) / 2f
                    val cy = (s.y0 + s.y1) / 2f
                    val halfW = ((s.x1 - s.x0) / 2f * factor).coerceIn(0.05f, 0.6f)
                    val halfH = ((s.y1 - s.y0) / 2f * factor).coerceIn(0.05f, 0.6f)
                    s.x0 = (cx - halfW).coerceIn(0f, 1f)
                    s.x1 = (cx + halfW).coerceIn(0f, 1f)
                    s.y0 = (cy - halfH).coerceIn(0f, 1f)
                    s.y1 = (cy + halfH).coerceIn(0f, 1f)
                }
            }
            invalidate()
        }

        private fun distance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }
}
