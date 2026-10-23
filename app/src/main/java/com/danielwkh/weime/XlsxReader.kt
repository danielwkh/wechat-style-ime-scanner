package com.danielwkh.weime

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream

/** 極簡 xlsx 讀取器：只讀第一個工作表，回傳每列的儲存格文字（依欄位位置排列）。 */
object XlsxReader {
    fun read(input: InputStream): List<List<String>> {
        var shared: List<String> = emptyList()
        var sheetBytes: ByteArray? = null
        var sheetName = ""
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == "xl/sharedStrings.xml") {
                    shared = parseShared(zip.readBytes())
                } else if (e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml")) {
                    if (sheetBytes == null || e.name < sheetName) {
                        sheetBytes = zip.readBytes()
                        sheetName = e.name
                    }
                }
            }
        }
        val sb = sheetBytes ?: throw IllegalStateException("不是有效的 xlsx 檔案")
        return parseSheet(sb, shared)
    }

    private fun parseShared(b: ByteArray): List<String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
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

    private fun parseSheet(b: ByteArray, shared: List<String>): List<List<String>> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(b), null)
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var col = 0
        var type = ""
        var value = StringBuilder()
        var inV = false
        var inT = false
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
                    "row" -> rows.add(row)
                }
            }
            ev = p.next()
        }
        return rows
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
