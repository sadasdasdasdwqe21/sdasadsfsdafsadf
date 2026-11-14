package app.obdforge.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import app.obdforge.core.analysis.Finding
import app.obdforge.core.analysis.Level
import app.obdforge.core.analysis.Series
import app.obdforge.core.analysis.Stats
import app.obdforge.core.assistant.AssistantReport
import app.obdforge.core.dtc.DtcKnowledge
import app.obdforge.core.pid.Pids
import app.obdforge.data.VehicleEntity
import app.obdforge.live.DiagState
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Share {
    fun exportDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

    fun share(ctx: Context, f: File, mime: String, title: String = "Share") {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri(ctx, f))
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(60)
}

/**
 * Multi-page A4 PDF diagnostic report drawn with Android's PdfDocument (no external library).
 */
class PdfReport(private val title: String) {
    private val doc = PdfDocument()
    private val pageW = 595; private val pageH = 842; private val margin = 40f
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var y = 0f
    private var pageNo = 0

    private val h1 = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(15, 23, 42); isAntiAlias = true }
    private val h2 = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; color = Color.rgb(8, 145, 178); isAntiAlias = true }
    private val body = Paint().apply { textSize = 9.5f; color = Color.rgb(30, 41, 59); isAntiAlias = true }
    private val small = Paint().apply { textSize = 8f; color = Color.rgb(100, 116, 139); isAntiAlias = true }
    private val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD }
    private val red = Paint(bold).apply { color = Color.rgb(220, 38, 38) }
    private val amber = Paint(bold).apply { color = Color.rgb(217, 119, 6) }
    private val green = Paint(bold).apply { color = Color.rgb(22, 163, 74) }
    private val line = Paint().apply { color = Color.rgb(203, 213, 225); strokeWidth = 0.8f }

    private fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        page = p; canvas = p.canvas; y = margin
        canvas!!.drawText("OBD Forge · $title", margin, pageH - 20f, small)
        canvas!!.drawText("Page $pageNo", pageW - margin - 40, pageH - 20f, small)
    }

    private fun ensure(h: Float) { if (page == null || y + h > pageH - 50) newPage() }

    fun heading(text: String) { ensure(30f); y += 6; canvas!!.drawText(text, margin, y + 13, h2); y += 20; canvas!!.drawLine(margin, y, pageW - margin, y, line); y += 6 }

    fun title(text: String, sub: String) { ensure(50f); canvas!!.drawText(text, margin, y + 18, h1); y += 26; canvas!!.drawText(sub, margin, y + 10, small); y += 18 }

    fun para(text: String, paint: Paint = body, indent: Float = 0f) {
        val maxW = pageW - 2 * margin - indent
        for (raw in text.split('\n')) {
            var rest = raw
            while (true) {
                var n = paint.breakText(rest, true, maxW, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp }
                ensure(13f)
                canvas!!.drawText(rest.substring(0, n).trimEnd(), margin + indent, y + 10, paint)
                y += 13
                rest = rest.substring(n).trimStart()
                if (rest.isEmpty()) break
            }
        }
    }

    fun kv(k: String, v: String) {
        ensure(13f)
        canvas!!.drawText(k, margin, y + 10, bold)
        val w = pageW - 2 * margin - 170
        val vv = if (body.measureText(v) > w) v.take(body.breakText(v, true, w, null)) else v
        canvas!!.drawText(vv, margin + 170, y + 10, body)
        y += 13
    }

    fun table(header: List<String>, rows: List<List<String>>, widths: List<Float>) {
        ensure(16f)
        var x = margin
        header.forEachIndexed { i, h -> canvas!!.drawText(h, x, y + 10, bold); x += widths[i] }
        y += 14; canvas!!.drawLine(margin, y, pageW - margin, y, line); y += 3
        for (r in rows) {
            ensure(13f)
            x = margin
            r.forEachIndexed { i, c ->
                val p = when { c == "FAIL" -> red; c == "MARGINAL" -> amber; c == "PASS" -> green; else -> body }
                val maxW = widths[i] - 4
                val txt = if (p.measureText(c) > maxW) c.take(p.breakText(c, true, maxW, null)) else c
                canvas!!.drawText(txt, x, y + 10, p); x += widths[i]
            }
            y += 13
        }
        y += 4
    }

    fun chart(series: List<Series>, h: Float = 150f) {
        ensure(h + 20)
        val c = canvas!!
        val left = margin; val right = pageW - margin; val top = y; val bottom = y + h
        c.drawRect(left, top, right, bottom, Paint().apply { style = Paint.Style.STROKE; color = Color.rgb(203, 213, 225) })
        val colors = listOf(Color.rgb(8, 145, 178), Color.rgb(234, 88, 12), Color.rgb(22, 163, 74), Color.rgb(147, 51, 234))
        val tMin = series.minOfOrNull { it.times.firstOrNull() ?: Long.MAX_VALUE } ?: 0
        val tMax = series.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 1
        series.take(4).forEachIndexed { i, s ->
            if (s.size < 2) return@forEachIndexed
            val mn = s.values.min(); val mx = s.values.max(); val span = if (mx - mn < 1e-9) 1.0 else mx - mn
            val path = Path()
            for (j in 0 until s.size) {
                val px = left + ((s.times[j] - tMin).toFloat() / (tMax - tMin).coerceAtLeast(1)) * (right - left)
                val py = bottom - ((s.values[j] - mn) / span).toFloat() * (h - 10) - 5
                if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1.2f; color = colors[i]; isAntiAlias = true })
            val ch = Pids.channel(s.key)
            c.drawText("${ch?.shortName ?: s.key} ${String.format(Locale.US, "%.1f–%.1f", mn, mx)} ${ch?.unit ?: ""}", left + 4 + i * 130, top + 10, Paint(small).apply { color = colors[i] })
        }
        y = bottom + 10
    }

    fun spacer(h: Float = 6f) { y += h }

    fun writeTo(f: File) {
        page?.let { doc.finishPage(it) }; page = null
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
    }

    companion object {
        private fun date(ms: Long) = SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))

        /** Full diagnostic report: vehicle, codes with guidance, readiness, freeze frame, Mode 06, analysis, assistant. */
        fun diagnostic(
            ctx: Context, vehicle: VehicleEntity?, diag: DiagState, protocol: String?, adapter: String?, simulated: Boolean,
            findings: List<Finding>, assistant: AssistantReport?, live: Map<String, Double>,
        ): File {
            val r = PdfReport("Diagnostic report")
            r.title("Diagnostic report", date(System.currentTimeMillis()) + if (simulated) " · SIMULATED DATA (no real vehicle)" else "")
            r.heading("Vehicle")
            r.kv("Vehicle", vehicle?.let { listOf(it.year?.toString() ?: "", it.make, it.model).filter { s -> s.isNotBlank() }.joinToString(" ").ifBlank { it.name } } ?: "Not set")
            vehicle?.let { r.kv("Engine", listOf(it.engine, it.engineCode).filter { s -> s.isNotBlank() }.joinToString(" · ").ifBlank { "-" }); r.kv("VIN", it.vin.ifBlank { "-" }); it.mileageKm?.let { m -> r.kv("Mileage", "${m.toInt()} km") } }
            r.kv("Protocol", protocol ?: "-"); r.kv("Adapter", adapter ?: "-")
            diag.vehicleInfo.forEach { r.kv(it.name, it.value.lines().first()) }

            r.heading("Trouble codes")
            if (diag.readAt == null) r.para("Codes were not read in this session.")
            else if (diag.allCodes.isEmpty()) r.para("No stored, pending or permanent codes (read ${date(diag.readAt)}).", green)
            for (d in diag.allCodes.distinctBy { it.code + it.kind }) {
                val info = DtcKnowledge.info(d.code.take(5), vehicle?.make)
                r.para("${d.code}  ${info.description}", bold)
                r.para("${d.kind.label} · ${info.system} · Severity: ${info.severity.label}", small, 10f)
                if (info.possibleCauses.isNotEmpty()) r.para("Possible causes (not confirmed): " + info.possibleCauses.take(5).joinToString("; "), body, 10f)
                if (info.diagnosticSteps.isNotEmpty()) r.para("First checks: " + info.diagnosticSteps.take(3).joinToString(" "), body, 10f)
                r.spacer(4f)
            }
            diag.readiness?.let { st ->
                r.heading("Readiness monitors (since codes cleared)")
                r.kv("MIL", if (st.milOn) "ON" else "Off"); r.kv("Reported DTC count", st.dtcCount.toString())
                r.table(listOf("Monitor", "Status"), st.monitors.filter { it.state != app.obdforge.core.pid.Readiness.State.NOT_SUPPORTED }.map { listOf(it.name, it.state.label) }, listOf(250f, 200f))
            }
            for (ff in diag.freezeFrames) {
                r.heading("Freeze frame (${ff.dtc ?: "no code"})")
                r.table(listOf("Parameter", "Value", "Current"), ff.values.map { (k, v) ->
                    val c = Pids.channel(k)
                    listOf(c?.name ?: k, "${c?.format(v) ?: v} ${c?.unit ?: ""}", live[k]?.let { "${c?.format(it) ?: it} ${c?.unit ?: ""}" } ?: "-")
                }, listOf(250f, 120f, 120f))
            }
            if (diag.mode06.isNotEmpty()) {
                r.heading("On-board monitor tests (Mode 06)")
                r.table(listOf("Monitor", "TID", "Value", "Min", "Max", "Result"), diag.mode06.map {
                    listOf(it.monitorName, it.tid.toString(16).uppercase(), fmt(it.value) + " " + it.unit, fmt(it.min), fmt(it.max), it.status)
                }, listOf(170f, 35f, 105f, 60f, 60f, 70f))
            }
            if (findings.isNotEmpty()) {
                r.heading("Engine analysis")
                for (f in findings.filter { it.level != Level.OK || it.section == "Fuel trims" }) {
                    r.para("[${f.level.label}] ${f.section}: ${f.title}" + if (f.estimated) " (estimate)" else "", when (f.level) { Level.ALERT -> red; Level.WARN -> amber; else -> bold })
                    if (f.detail.isNotBlank()) r.para(f.detail, body, 10f)
                }
            }
            assistant?.let { a ->
                r.heading("Diagnostic assistant (hypotheses, not confirmed faults)")
                r.para(a.headline, bold)
                a.conditions.forEach { r.para("• $it") }
                a.hypotheses.take(5).forEach { h ->
                    r.para("${h.title} — ${h.strength}", bold)
                    if (h.evidenceFor.isNotEmpty()) r.para("For: " + h.evidenceFor.joinToString("; "), body, 10f)
                    if (h.evidenceAgainst.isNotEmpty()) r.para("Against: " + h.evidenceAgainst.joinToString("; "), body, 10f)
                }
                if (a.testPlan.isNotEmpty()) {
                    r.para("Suggested test order:", bold)
                    a.testPlan.take(6).forEachIndexed { i, (h, t) -> r.para("${i + 1}. ${t.action} (supports \"$h\" if: ${t.confirmsIf}; rules it out if: ${t.eliminatesIf})", body, 10f) }
                }
                r.para(a.disclaimer, small)
            }
            val f = File(Share.exportDir(ctx), "OBDForge_report_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.pdf")
            r.writeTo(f)
            return f
        }

        /** Report for one recorded session: stats table and charts. */
        fun session(ctx: Context, title: String, vehicle: String, start: Long, notes: String, simulated: Boolean, data: Map<String, Series>, findings: List<Finding>): File {
            val r = PdfReport("Session report")
            r.title(title, "$vehicle · ${date(start)}" + if (simulated) " · SIMULATED DATA" else "")
            if (notes.isNotBlank()) r.para(notes)
            r.heading("Channel statistics")
            r.table(listOf("Channel", "Min", "Max", "Mean", "Median", "Std dev", "Unit"), data.map { (k, s) ->
                val st = Stats.describe(s); val c = Pids.channel(k)
                listOf((c?.name ?: k) + if (c != null && c.source != app.obdforge.core.pid.Channel.Source.ECU) " [${c.source.badge}]" else "", fmt(st.min), fmt(st.max), fmt(st.mean), fmt(st.median), fmt(st.stdDev), c?.unit ?: "")
            }, listOf(190f, 55f, 55f, 55f, 55f, 55f, 50f))
            val groups = listOf(listOf("rpm", "speed"), listOf("stft1", "ltft1", "trim_total1"), listOf("boost", "map", "maf"), listOf("ect", "iat"))
            r.heading("Charts")
            for (g in groups) { val ss = g.mapNotNull { data[it] }; if (ss.isNotEmpty()) r.chart(ss) }
            if (findings.isNotEmpty()) {
                r.heading("Analysis")
                findings.forEach { f -> r.para("[${f.level.label}] ${f.title}" + if (f.estimated) " (estimate)" else "", bold); if (f.detail.isNotBlank()) r.para(f.detail, Paint().apply { textSize = 9f }, 10f) }
            }
            val f = File(Share.exportDir(ctx), "OBDForge_${Share.safeName(title)}.pdf")
            r.writeTo(f)
            return f
        }

        fun fmt(v: Double): String = if (v.isNaN()) "-" else if (kotlin.math.abs(v) >= 1000) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.2f", v)
    }
}

/** Renders series to a PNG for sharing graphs as images. */
object ChartImage {
    fun render(ctx: Context, series: List<Series>, title: String, w: Int = 1600, h: Int = 900, dark: Boolean = true): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(if (dark) Color.rgb(11, 15, 20) else Color.WHITE)
        val textP = Paint().apply { color = if (dark) Color.WHITE else Color.BLACK; textSize = 34f; isAntiAlias = true }
        c.drawText(title, 40f, 60f, textP)
        val colors = listOf(Color.rgb(34, 211, 238), Color.rgb(249, 115, 22), Color.rgb(74, 222, 128), Color.rgb(192, 132, 252), Color.rgb(250, 204, 21), Color.rgb(244, 114, 182))
        val left = 40f; val right = w - 40f; val top = 140f; val bottom = h - 60f
        val grid = Paint().apply { color = if (dark) Color.rgb(40, 50, 60) else Color.rgb(220, 220, 220); strokeWidth = 2f }
        for (i in 0..4) { val yy = top + (bottom - top) * i / 4; c.drawLine(left, yy, right, yy, grid) }
        val tMin = series.minOfOrNull { it.times.firstOrNull() ?: Long.MAX_VALUE } ?: 0
        val tMax = series.maxOfOrNull { it.times.lastOrNull() ?: 0 } ?: 1
        series.take(6).forEachIndexed { i, s ->
            if (s.size < 2) return@forEachIndexed
            val mn = s.values.min(); val mx = s.values.max(); val span = if (mx - mn < 1e-9) 1.0 else mx - mn
            val p = Path()
            for (j in 0 until s.size) {
                val px = left + (s.times[j] - tMin).toFloat() / (tMax - tMin).coerceAtLeast(1) * (right - left)
                val py = bottom - ((s.values[j] - mn) / span).toFloat() * (bottom - top)
                if (j == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            c.drawPath(p, Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = colors[i]; isAntiAlias = true })
            val ch = Pids.channel(s.key)
            c.drawText("${ch?.shortName ?: s.key}: ${String.format(Locale.US, "%.1f – %.1f", mn, mx)} ${ch?.unit ?: ""}", 40f + (i % 3) * 520f, 96f + (i / 3) * 34f, Paint(textP).apply { color = colors[i]; textSize = 28f })
        }
        c.drawText("${String.format(Locale.US, "%.1f", (tMax - tMin) / 1000.0)} s", right - 160f, h - 20f, Paint(textP).apply { textSize = 24f })
        val f = File(Share.exportDir(ctx), "OBDForge_${Share.safeName(title)}.png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }
}
