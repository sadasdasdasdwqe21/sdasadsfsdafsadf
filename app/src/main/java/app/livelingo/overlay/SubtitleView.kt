package app.livelingo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.view.View
import app.livelingo.core.CaptionDisplay
import app.livelingo.settings.DisplayMode
import app.livelingo.settings.SubtitlePreferences
import app.livelingo.settings.TextAlign
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws subtitles the way film subtitles look: optional rounded translucent box, text outline,
 * soft shadow, and only the most recent lines. Provisional words are drawn slightly dimmer so a
 * finished sentence is never visually overwritten by a guess.
 *
 * The same view renders the live overlay and the preview in Settings.
 */
class SubtitleView(context: Context) : View(context) {

    var style: SubtitlePreferences = SubtitlePreferences()
        set(value) { if (field != value) { field = value; invalidateLayouts() } }

    var caption: CaptionDisplay = CaptionDisplay()
        set(value) { if (field != value) { field = value; invalidateLayouts() } }

    /** Text shown when there is nothing to display (preview, or while moving the overlay). */
    var placeholder: String? = null
        set(value) { if (field != value) { field = value; invalidateLayouts() } }

    /** Dashed frame shown while the overlay can be moved and resized. */
    var showFrame: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    private val transFill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val transStroke = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val origFill = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val origStroke = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xCC4FD1C5.toInt()
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private var transBlock: Block? = null
    private var origBlock: Block? = null
    private var builtForWidth = -1
    private val rect = RectF()

    private class Block(val fill: StaticLayout, val stroke: StaticLayout?, val maxLines: Int) {
        val firstLine get() = max(0, fill.lineCount - maxLines)
        val top get() = fill.getLineTop(firstLine)
        val bottom get() = fill.getLineBottom(fill.lineCount - 1)
        val height get() = bottom - top
    }

    private val padH get() = (14 * density).roundToInt()
    private val padV get() = (8 * density).roundToInt()
    private val blockGap get() = (4 * density).roundToInt()

    private fun invalidateLayouts() {
        builtForWidth = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> (320 * density).roundToInt()
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        build(width)
        val content = contentHeight()
        val minH = (style.minHeightDp * density).roundToInt()
        val h = if (content == 0 && !showFrame && minH == 0) 0 else max(content + padV * 2, minH)
        setMeasuredDimension(width, h)
    }

    private fun contentHeight(): Int {
        val t = transBlock?.height ?: 0
        val o = origBlock?.height ?: 0
        return t + o + if (t > 0 && o > 0) blockGap else 0
    }

    private fun build(width: Int) {
        if (width == builtForWidth) return
        builtForWidth = width
        val inner = max(1, width - padH * 2)
        val s = style
        val typeface = Typeface.create(s.font.family, if (s.bold) Typeface.BOLD else Typeface.NORMAL)
        val align = when (s.align) {
            TextAlign.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        }

        configure(transFill, transStroke, s.fontSizeSp, typeface, s.textColor)
        configure(origFill, origStroke, s.fontSizeSp * 0.78f, Typeface.create(s.font.family, Typeface.NORMAL), s.originalTextColor)

        var translationText = caption.translationFinal to caption.translationProvisional
        var originalText = caption.originalFinal to caption.originalProvisional
        if (caption.isEmpty && placeholder != null) {
            translationText = placeholder!! to ""
            originalText = "" to ""
        }

        transBlock = makeBlock(translationText, transFill, transStroke, s.textColor, inner, align, s.maxLines)
        origBlock = if (s.displayMode == DisplayMode.ORIGINAL_AND_TRANSLATION) {
            makeBlock(originalText, origFill, origStroke, s.originalTextColor, inner, align, minOf(2, s.maxLines))
        } else null
    }

    private fun configure(fill: TextPaint, stroke: TextPaint, sizeSp: Float, tf: Typeface, color: Int) {
        val s = style
        fill.textSize = sizeSp * scaled
        fill.typeface = tf
        fill.color = color
        fill.style = Paint.Style.FILL
        if (s.shadow) fill.setShadowLayer(3f * density, 0f, 1.2f * density, 0xE6000000.toInt()) else fill.clearShadowLayer()

        stroke.textSize = fill.textSize
        stroke.typeface = tf
        stroke.style = Paint.Style.STROKE
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.strokeWidth = s.outlineWidthDp * density * 2f // half of the stroke sits under the fill
        stroke.color = s.outlineColor
    }

    private fun makeBlock(
        text: Pair<String, String>,
        fill: TextPaint,
        stroke: TextPaint,
        color: Int,
        width: Int,
        align: Layout.Alignment,
        maxLines: Int,
    ): Block? {
        val (final, provisional) = text
        val plain = (final + provisional).trim()
        if (plain.isEmpty()) return null
        val leading = (final + provisional).length - (final + provisional).trimStart().length
        val finalTrimmed = final.drop(minOf(leading, final.length))

        val spannable = SpannableStringBuilder(plain)
        val provStart = finalTrimmed.length.coerceAtMost(plain.length)
        if (provStart < plain.length) {
            val dim = Color.argb((Color.alpha(color) * 0.72f).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))
            spannable.setSpan(ForegroundColorSpan(dim), provStart, plain.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val fillLayout = layout(spannable, fill, width, align)
        val strokeLayout = if (style.outline) layout(plain, stroke, width, align) else null
        return Block(fillLayout, strokeLayout, maxLines)
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setLineSpacing(0f, style.lineSpacing)
            .setIncludePad(false)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .build()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0) return
        val s = style
        val radius = s.cornerRadiusDp * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())

        if (s.backgroundOpacity > 0f && contentHeight() > 0) {
            val content = contentHeight() + padV * 2
            // Shrink the box to the widest visible line for a cleaner look.
            val textWidth = visibleTextWidth()
            val boxW = if (textWidth > 0) minOf(width.toFloat(), textWidth + padH * 2f) else width.toFloat()
            val left = when (s.align) {
                TextAlign.START -> 0f
                TextAlign.CENTER -> (width - boxW) / 2f
                TextAlign.END -> width - boxW
            }
            val top = (height - content) / 2f
            rect.set(left, top, left + boxW, top + content)
            bgPaint.color = s.backgroundColor
            bgPaint.alpha = (s.backgroundOpacity * 255).roundToInt()
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }

        var y = (height - contentHeight()) / 2f
        origBlock?.let { y = drawBlock(canvas, it, y) + blockGap }
        transBlock?.let { drawBlock(canvas, it, y) }

        if (showFrame) {
            rect.set(1f, 1f, width - 1f, height - 1f)
            canvas.drawRoundRect(rect, radius, radius, framePaint)
        }
    }

    private fun drawBlock(canvas: Canvas, b: Block, y: Float): Float {
        canvas.save()
        canvas.translate(padH.toFloat(), y - b.top)
        canvas.clipRect(0f, b.top.toFloat(), b.fill.width.toFloat(), b.bottom.toFloat())
        b.stroke?.draw(canvas)
        b.fill.draw(canvas)
        canvas.restore()
        return y + b.height
    }

    private fun visibleTextWidth(): Float {
        var w = 0f
        for (b in listOfNotNull(transBlock, origBlock)) {
            for (i in b.firstLine until b.fill.lineCount) w = max(w, b.fill.getLineWidth(i))
        }
        return w
    }
}
