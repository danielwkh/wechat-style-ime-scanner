package com.danielwkh.weime

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipFile

/** 極簡 xlsx 讀取器（串流方式，大檔案也不會爆記憶體）。 */
object XlsxReader {
    /** 所有工作表的檔名，例如 xl/worksheets/sheet1.xml。 */
    fun sheetEntries(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedWith(compareBy<String>({ it.length }, { it }))
            .toList()

    fun sharedStrings(zip: ZipFile): List<String> {
        val e = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        return zip.getInputStream(e).use { parseShared(it) }
    }

    /** 逐列讀取工作表；limit 達到就停止（用來取樣）。每列依欄位位置排列，空格子是空字串。 */
    fun forEachRow(
        zip: ZipFile,
        entry: String,
        shared: List<String>,
        limit: Int = Int.MAX_VALUE,
        onRow: (List<String>) -> Unit
    ) {
        val e = zip.getEntry(entry) ?: return
        zip.getInputStream(e).use { stream -> parseSheet(stream, shared, limit, onRow) }
    }

    private fun parseShared(input: InputStream): List<String> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val out = ArrayList<String>()
        var sb: StringBuilder? = null
        var inT = false
        var inRph = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> sb = StringBuilder()
                    "t" -> inT = true
                    "rPh" -> inRph = true
                }
                XmlPullParser.TEXT -> if (inT && !inRph) sb?.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "t" -> inT = false
                    "rPh" -> inRph = false
                    "si" -> { out.add(sb?.toString() ?: ""); sb = null }
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(
        input: InputStream,
        shared: List<String>,
        limit: Int,
        onRow: (List<String>) -> Unit
    ) {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        var row = ArrayList<String>()
        var col = 0
        var type = ""
        var value = StringBuilder()
        var inV = false
        var inT = false
        var count = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = ArrayList()
                    "c" -> {
                        val r = p.getAttributeValue(null, "r") ?: ""
                        col = if (r.isEmpty()) row.size else colIndex(r)
                        type = p.getAttributeValue(null, "t") ?: ""
                        value = StringBuilder()
                    }
                    "v" -> inV = true
                    "t" -> inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        while (row.size < col) row.add("")
                        val raw = value.toString()
                        val s = when (type) {
                            "s" -> shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
                            "str", "inlineStr", "b" -> raw
                            else -> numStr(raw)
                        }
                        if (row.size == col) row.add(s) else if (col < row.size) row[col] = s
                    }
                    "row" -> {
                        onRow(row)
                        count++
                        if (count >= limit) return
                    }
                }
            }
            ev = p.next()
        }
    }

    private fun numStr(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            BigDecimal(t).stripTrailingZeros().toPlainString()
        } catch (_: Exception) {
            t
        }
    }

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) {
            if (ch in 'A'..'Z') n = n * 26 + (ch - 'A' + 1) else break
        }
        return maxOf(n - 1, 0)
    }
}
