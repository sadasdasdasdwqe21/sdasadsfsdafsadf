package app.livelingo.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.livelingo.AppContainer
import app.livelingo.core.AudioSourceType
import app.livelingo.settings.SubtitlePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Operations the overlay controls can trigger. Implemented by the foreground service. */
interface OverlayActions {
    fun pause()
    fun resume()
    fun stop()
    fun setTarget(code: String)
    fun setSubtitlesVisible(visible: Boolean)
    fun setLocked(locked: Boolean)
    fun switchSource(type: AudioSourceType)
    fun openSettings()
}

/**
 * Owns the floating windows (subtitles, control bubble, compact panel) drawn over other apps
 * with TYPE_APPLICATION_OVERLAY.
 *
 * Touch policy:
 *  - Locked (default): the subtitle window is FLAG_NOT_TOUCHABLE, so every tap goes to the app
 *    underneath. Android 12+ only lets taps pass through another app's overlay when that window's
 *    opacity is at most the system limit (0.8), so the window alpha is capped while locked.
 *  - Unlocked: the subtitle box can be dragged and resized; taps outside it still pass through.
 */
class SubtitleOverlayManager(
    private val context: Context,
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val actions: OverlayActions,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var subtitleView: SubtitleView? = null
    private var handle: View? = null
    private var doneChip: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var bubble: ComposeView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleOwner: OverlayLifecycleOwner? = null

    private var panel: ComposeView? = null
    private var panelOwner: OverlayLifecycleOwner? = null

    private val jobs = mutableListOf<Job>()
    private var lastStyle: SubtitlePreferences? = null
    private var dragging = false

    val canDraw: Boolean get() = Settings.canDrawOverlays(context)

    fun show(): Boolean {
        if (!canDraw) return false
        if (root != null) return true
        createSubtitleWindow()
        jobs += scope.launch {
            c.settings.settings.map { it.subtitle }.distinctUntilChanged().collect { applyStyle(it) }
        }
        jobs += scope.launch {
            c.session.captions.collect { subtitleView?.caption = it }
        }
        jobs += scope.launch {
            c.session.status.map { it.subtitlesVisible }.distinctUntilChanged().collect { visible ->
                root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
        }
        return true
    }

    fun hide() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        closePanel()
        removeBubble()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        subtitleView = null
        params = null
        lastStyle = null
    }

    /** Re-applies fraction-based position after rotation or a screen size change. */
    fun onConfigurationChanged() {
        lastStyle?.let { s ->
            lastStyle = null
            applyStyle(s)
        }
    }

    // --- Subtitle window -----------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun createSubtitleWindow() {
        val view = SubtitleView(context)
        val frame = FrameLayout(context)
        frame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val handleSize = (30 * density).roundToInt()
        val h = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4FD1C5.toInt())
                setStroke((2 * density).roundToInt(), 0xFF0B0F17.toInt())
            }
            contentDescription = "Resize subtitles"
            visibility = View.GONE
        }
        frame.addView(h, FrameLayout.LayoutParams(handleSize, handleSize, Gravity.BOTTOM or Gravity.END))

        val done = TextView(context).apply {
            text = "Lock"
            setTextColor(0xFF00201D.toInt())
            textSize = 13f
            setPadding((12 * density).roundToInt(), (5 * density).roundToInt(), (12 * density).roundToInt(), (5 * density).roundToInt())
            background = GradientDrawable().apply {
                cornerRadius = 40 * density
                setColor(0xFF4FD1C5.toInt())
            }
            visibility = View.GONE
            setOnClickListener { actions.setLocked(true) }
        }
        frame.addView(done, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags() or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "LiveLingo subtitles"
        }

        frame.setOnTouchListener(DragListener())
        h.setOnTouchListener(ResizeListener())

        wm.addView(frame, lp)
        root = frame
        subtitleView = view
        handle = h
        doneChip = done
        params = lp
    }

    private fun baseFlags() = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

    private fun maxPassThroughAlpha(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
        } else 1f

    private fun applyStyle(s: SubtitlePreferences) {
        val view = subtitleView ?: return
        val lp = params ?: return
        val old = lastStyle
        lastStyle = s
        view.style = s
        val unlocked = !s.locked
        view.showFrame = unlocked
        view.placeholder = if (unlocked) "Drag to move · pull the dot to resize" else null
        handle?.visibility = if (unlocked) View.VISIBLE else View.GONE
        doneChip?.visibility = if (unlocked) View.VISIBLE else View.GONE

        if (!dragging) {
            val (sw, sh) = screenSize()
            lp.width = (sw * s.widthFraction).roundToInt().coerceAtLeast((160 * density).roundToInt())
            lp.x = (sw * s.xFraction).roundToInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
            lp.y = (sh * s.yFraction).roundToInt().coerceIn(0, (sh - (48 * density).roundToInt()).coerceAtLeast(0))
        }
        lp.flags = baseFlags() or if (unlocked) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.alpha = if (!unlocked && s.tapThrough) minOf(1f, maxPassThroughAlpha()) else 1f
        root?.let { runCatching { wm.updateViewLayout(it, lp) } }

        if (old == null || old.showBubble != s.showBubble) {
            if (s.showBubble) showBubble(s) else removeBubble()
        }
    }

    fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    private inner class DragListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var origX = 0
        private var origY = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startX = e.rawX; startY = e.rawY; origX = lp.x; origY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, sh) = screenSize()
                    lp.x = (origX + (e.rawX - startX)).roundToInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                    lp.y = (origY + (e.rawY - startY)).roundToInt().coerceIn(0, (sh - v.height).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    persistGeometry()
                }
            }
            return true
        }
    }

    private inner class ResizeListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var startW = 0
        private var startMinH = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = params ?: return false
            val view = subtitleView ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startX = e.rawX; startY = e.rawY; startW = lp.width
                    startMinH = maxOf(view.style.minHeightDp, view.height / density)
                }
                MotionEvent.ACTION_MOVE -> {
                    val (sw, _) = screenSize()
                    lp.width = (startW + (e.rawX - startX)).roundToInt().coerceIn((160 * density).roundToInt(), sw - lp.x)
                    val minH = (startMinH + (e.rawY - startY) / density).coerceIn(0f, 400f)
                    view.style = view.style.copy(minHeightDp = if (abs(minH - startMinH) < 4f) view.style.minHeightDp else minH)
                    root?.let { runCatching { wm.updateViewLayout(it, lp) } }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    persistGeometry(minHeightDp = view.style.minHeightDp)
                }
            }
            return true
        }
    }

    private fun persistGeometry(minHeightDp: Float? = null) {
        val lp = params ?: return
        val (sw, sh) = screenSize()
        scope.launch {
            c.settings.updateSubtitle {
                it.copy(
                    xFraction = lp.x / sw.toFloat(),
                    yFraction = lp.y / sh.toFloat(),
                    widthFraction = lp.width / sw.toFloat(),
                    minHeightDp = minHeightDp ?: it.minHeightDp,
                )
            }
        }
    }

    // --- Control bubble and panel ---------------------------------------------------------------

    private fun showBubble(s: SubtitlePreferences) {
        if (bubble != null) return
        val owner = OverlayLifecycleOwner()
        val size = (52 * density).roundToInt()
        val (sw, sh) = screenSize()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw * s.bubbleXFraction).roundToInt().coerceIn(0, sw - size)
            y = (sh * s.bubbleYFraction).roundToInt().coerceIn(0, sh - size)
            title = "LiveLingo controls"
        }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            owner.attach(this)
            setContent { ControlBubble(status = c.session.status) }
            setOnTouchListener(BubbleTouchListener())
        }
        owner.create()
        wm.addView(view, lp)
        bubble = view
        bubbleParams = lp
        bubbleOwner = owner
    }

    /** Uses raw screen coordinates so the bubble follows the finger while its window moves. */
    private inner class BubbleTouchListener : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var origX = 0
        private var origY = 0
        private var moved = false
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; origX = lp.x; origY = lp.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    val dy = e.rawY - startY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        closePanel()
                    }
                    if (moved) {
                        val (sw, sh) = screenSize()
                        lp.x = (origX + dx).roundToInt().coerceIn(0, sw - lp.width)
                        lp.y = (origY + dy).roundToInt().coerceIn(0, sh - lp.height)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) persistBubble() else togglePanel()
                MotionEvent.ACTION_CANCEL -> if (moved) persistBubble()
            }
            return true
        }
    }

    private fun persistBubble() {
        val lp = bubbleParams ?: return
        val (sw, sh) = screenSize()
        scope.launch { c.settings.updateSubtitle { it.copy(bubbleXFraction = lp.x / sw.toFloat(), bubbleYFraction = lp.y / sh.toFloat()) } }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubbleOwner?.destroy()
        bubble = null
        bubbleParams = null
        bubbleOwner = null
    }

    private var panelClosedAt = 0L

    private fun togglePanel() {
        when {
            panel != null -> closePanel()
            // A tap on the bubble first closes the panel as an "outside" touch; don't reopen it.
            android.os.SystemClock.uptimeMillis() - panelClosedAt < 400 -> Unit
            else -> openPanel()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openPanel() {
        val bp = bubbleParams ?: return
        val owner = OverlayLifecycleOwner()
        val (sw, sh) = screenSize()
        val panelWidth = minOf((320 * density).roundToInt(), sw - (16 * density).roundToInt())
        val lp = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags() or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val bubbleOnRight = bp.x > sw / 2
            x = if (bubbleOnRight) (bp.x - panelWidth - (8 * density).roundToInt()).coerceAtLeast(0)
            else (bp.x + bp.width + (8 * density).roundToInt()).coerceAtMost(sw - panelWidth)
            y = bp.y.coerceIn(0, (sh - (420 * density).roundToInt()).coerceAtLeast(0))
            title = "LiveLingo panel"
        }
        val view = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            owner.attach(this)
            setContent {
                ControlPanel(
                    status = c.session.status,
                    settings = c.settings.settings,
                    actions = actions,
                    onClose = { closePanel() },
                )
            }
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) v.post { closePanel() }
                false
            }
        }
        owner.create()
        wm.addView(view, lp)
        panel = view
        panelOwner = owner
    }

    fun closePanel() {
        if (panel != null) panelClosedAt = android.os.SystemClock.uptimeMillis()
        panel?.let { runCatching { wm.removeView(it) } }
        panelOwner?.destroy()
        panel = null
        panelOwner = null
    }
}
