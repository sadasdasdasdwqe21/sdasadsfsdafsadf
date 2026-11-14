package app.obdforge.core.export

import app.obdforge.core.analysis.Series
import app.obdforge.core.analysis.Stats
import app.obdforge.core.pid.Channel
import app.obdforge.core.pid.Pids
import app.obdforge.core.util.Json
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Everything needed to export one recorded session. */
data class SessionExport(
    val title: String,
    val vehicle: String,
    val startEpochMs: Long,
    val notes: String,
    val simulated: Boolean,
    /** channel key -> series with times relative to session start (ms) */
    val series: Map<String, Series>,
)

object Exporters {
    private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))

    private fun header(k: String): String {
        val c = Pids.channel(k) ?: return k
        val tag = if (c.source != Channel.Source.ECU) " [${c.source.badge}]" else ""
        return "${c.name} (${c.unit})$tag"
    }

    /** Union of all sample times across channels; each row holds the latest value of each channel (blank before its first sample). */
    fun wideRows(e: SessionExport, maxRows: Int = Int.MAX_VALUE): Pair<List<String>, List<List<Any?>>> {
        val keys = e.series.keys.sortedBy { Pids.channels.indexOfFirst { c -> c.key == it }.let { i -> if (i < 0) 9999 else i } }
        val times = java.util.TreeSet<Long>()
        e.series.values.forEach { s -> s.times.forEach { times.add(it) } }
        val cursor = IntArray(keys.size)
        val rows = ArrayList<List<Any?>>()
        for (t in times) {
            if (rows.size >= maxRows) break
            val row = ArrayList<Any?>(keys.size + 2)
            row.add(t / 1000.0); row.add(iso(e.startEpochMs + t))
            keys.forEachIndexed { i, k ->
                val s = e.series[k]!!
                while (cursor[i] + 1 < s.size && s.times[cursor[i] + 1] <= t) cursor[i]++
                row.add(if (s.size > 0 && s.times[cursor[i]] <= t) s.values[cursor[i]] else null)
            }
            rows.add(row)
        }
        return (listOf("Time (s)", "Timestamp") + keys.map { header(it) }) to rows
    }

    fun csv(e: SessionExport, out: OutputStream) {
        val w = out.bufferedWriter(Charsets.UTF_8)
        w.write("﻿") // BOM so Excel opens UTF-8 (°, λ) correctly
        w.write("# ${e.title} | ${e.vehicle} | start ${iso(e.startEpochMs)}${if (e.simulated) " | SIMULATED DATA" else ""}\n")
        if (e.notes.isNotBlank()) w.write("# ${e.notes.replace('\n', ' ')}\n")
        val (head, rows) = wideRows(e)
        w.write(head.joinToString(",") { csvCell(it) }); w.write("\n")
        for (r in rows) {
            w.write(r.joinToString(",") { v -> when (v) { null -> ""; is Double -> String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.'); else -> csvCell(v.toString()) } })
            w.write("\n")
        }
        w.flush()
    }

    private fun csvCell(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun json(e: SessionExport): String {
        val channels = e.series.map { (k, s) ->
            val c = Pids.channel(k)
            val st = Stats.describe(s)
            mapOf(
                "key" to k, "name" to (c?.name ?: k), "unit" to (c?.unit ?: ""), "source" to (c?.source?.name ?: "ECU"),
                "stats" to mapOf("min" to st.min, "max" to st.max, "mean" to st.mean, "median" to st.median, "stdDev" to st.stdDev),
                "t_ms" to s.times.toList(), "values" to s.values.toList(),
            )
        }
        return Json.write(mapOf(
            "format" to "obdforge-session-1", "title" to e.title, "vehicle" to e.vehicle,
            "start" to iso(e.startEpochMs), "startEpochMs" to e.startEpochMs, "simulated" to e.simulated,
            "notes" to e.notes, "channels" to channels,
        ), pretty = true)
    }

    /** Minimal but standards-compliant .xlsx (Office Open XML) with a data sheet and a summary sheet. */
    fun xlsx(e: SessionExport, out: OutputStream, maxRows: Int = 1_000_000) {
        val (head, rows) = wideRows(e, maxRows - 1)
        val summary = ArrayList<List<Any?>>()
        summary.add(listOf("Title", e.title)); summary.add(listOf("Vehicle", e.vehicle)); summary.add(listOf("Start", iso(e.startEpochMs)))
        summary.add(listOf("Data", if (e.simulated) "SIMULATED" else "Vehicle")); summary.add(listOf("Notes", e.notes)); summary.add(listOf<Any?>())
        summary.add(listOf("Channel", "Unit", "Source", "Min", "Max", "Mean", "Median", "Std dev", "Samples"))
        for ((k, s) in e.series) {
            val c = Pids.channel(k); val st = Stats.describe(s)
            summary.add(listOf(c?.name ?: k, c?.unit ?: "", c?.source?.badge ?: "ECU", st.min, st.max, st.mean, st.median, st.stdDev, st.count.toDouble()))
        }
        XlsxWriter.write(out, listOf("Data" to (listOf(head) + rows), "Summary" to summary))
    }
}

object XlsxWriter {
    fun write(out: OutputStream, sheets: List<Pair<String, List<List<Any?>>>>) {
        val zip = ZipOutputStream(out)
        fun put(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        put("[Content_Types].xml", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
            sheets.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
            append("</Types>")
        })
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        put("xl/workbook.xml", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            sheets.forEachIndexed { i, s -> append("""<sheet name="${esc(s.first.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
            append("</sheets></workbook>")
        })
        put("xl/_rels/workbook.xml.rels", buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            sheets.indices.forEach { append("""<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
            append("""<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("</Relationships>")
        })
        put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>""")
        sheets.forEachIndexed { idx, (_, rows) ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet${idx + 1}.xml"))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            rows.forEachIndexed { r, row ->
                w.write("<row r=\"${r + 1}\">")
                row.forEachIndexed { c, v ->
                    val ref = col(c) + (r + 1)
                    val style = if (r == 0 || (idx == 1 && row.size > 5 && r > 0 && v is String && row[0] == "Channel")) " s=\"1\"" else ""
                    when (v) {
                        null -> Unit
                        is Number -> { val d = v.toDouble(); if (!d.isNaN() && !d.isInfinite()) w.write("<c r=\"$ref\"$style><v>$d</v></c>") }
                        else -> w.write("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                    }
                }
                w.write("</row>")
            }
            w.write("</sheetData></worksheet>")
            w.flush()
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    fun col(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    fun toBytes(sheets: List<Pair<String, List<List<Any?>>>>): ByteArray = ByteArrayOutputStream().also { write(it, sheets) }.toByteArray()
}
