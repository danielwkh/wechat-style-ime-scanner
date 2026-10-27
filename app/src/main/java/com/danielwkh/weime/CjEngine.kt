package com.danielwkh.weime

import android.content.Context

/**
 * 速成／倉頡引擎。
 * 碼表：Rime 的 cangjie5.base.dict.yaml（倉頡五代單字碼表，LGPL），速成碼由倉頡碼取「首＋尾」兩碼推出；
 * 字頻：rime-essay 的單字字頻（freq.tsv），用來排候選字順序。
 * 兩個檔案都放在 assets，由 scripts/fetch-ime-data.sh 在編譯時下載。
 */
object CjEngine {
    const val QUICK = 1
    const val CANGJIE = 2

    // a–z 對應的倉頡字根
    private const val RADICALS = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜符"
    private val WS = Regex("\\s+")

    @Volatile private var ready = false
    private var cj: Map<String, List<String>> = emptyMap()
    private var quick: Map<String, List<String>> = emptyMap()
    private var cjKeys: Array<String> = emptyArray()
    private var quickKeys: Array<String> = emptyArray()
    private var freq: Map<String, Long> = emptyMap()

    fun isReady() = ready

    fun radical(c: Char): Char = RADICALS[(c.lowercaseChar() - 'a').coerceIn(0, 25)]

    fun display(code: CharSequence): String {
        val sb = StringBuilder()
        for (ch in code) sb.append(radical(ch))
        return sb.toString()
    }

    /** 速成最多 2 碼，倉頡最多 5 碼。 */
    fun maxLen(scheme: Int) = if (scheme == QUICK) 2 else 5

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (ready) return true
        val app = ctx.applicationContext
        val text = try {
            app.assets.open("cangjie5.base.dict.yaml").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return false
        }
        val f = HashMap<String, Long>()
        try {
            app.assets.open("freq.tsv").bufferedReader().useLines { seq ->
                for (line in seq) {
                    val i = line.indexOf('\t')
                    if (i <= 0) continue
                    val w = line.substring(i + 1).trim().toLongOrNull() ?: continue
                    f[line.substring(0, i)] = w
                }
            }
        } catch (_: Exception) {
            // 沒有字頻檔也能用，只是候選字順序較差
        }

        val lines = text.lines()
        val marker = lines.indexOfFirst { it.trim() == "..." }
        val start = if (marker in 0..80) marker + 1 else 0
        val c = HashMap<String, MutableList<String>>()
        val q = HashMap<String, MutableList<String>>()
        for (k in start until lines.size) {
            val line = lines[k]
            if (line.isEmpty() || line[0] == '#') continue
            val p = line.trim().split(WS)
            if (p.size < 2) continue
            val ch = p[0]
            val code = p[1]
            if (ch == "-" || ch.codePointCount(0, ch.length) != 1) continue
            if (code.length !in 1..6 || !code.all { it in 'a'..'z' }) continue
            add(c, code, ch)
            // 速成＝倉頡碼的首尾二碼（1～2 碼的字就用原碼；z 開頭不派生）
            val qc: String? = when {
                code.length <= 2 -> code
                code[0] == 'z' -> null
                else -> "" + code[0] + code[code.length - 1]
            }
            if (qc != null) add(q, qc, ch)
        }
        if (c.isEmpty()) return false

        cj = finish(c, f)
        quick = finish(q, f)
        cjKeys = cj.keys.sorted().toTypedArray()
        quickKeys = quick.keys.sorted().toTypedArray()
        freq = f
        ready = true
        return true
    }

    private fun add(m: HashMap<String, MutableList<String>>, code: String, ch: String) {
        val l = m.getOrPut(code) { ArrayList() }
        if (!l.contains(ch)) l.add(ch)
    }

    private fun finish(m: HashMap<String, MutableList<String>>, f: Map<String, Long>): Map<String, List<String>> {
        val out = HashMap<String, List<String>>(m.size * 2)
        for ((k, v) in m) out[k] = v.sortedByDescending { f[it] ?: 0L }
        return out
    }

    private fun lowerBound(a: Array<String>, key: String): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 回傳候選字（字，用掉的碼數）。先列「剛好吻合」的，再補以這串碼開頭的字（依字頻）。 */
    fun lookup(ctx: Context, scheme: Int, code: String): List<Pair<String, Int>> {
        if (code.isEmpty()) return emptyList()
        if (!ready && !load(ctx)) return emptyList()
        val map = if (scheme == QUICK) quick else cj
        val keys = if (scheme == QUICK) quickKeys else cjKeys
        val out = LinkedHashSet<String>()
        map[code]?.let { out.addAll(it) }
        if (out.size < 40) {
            val extra = ArrayList<String>()
            var i = lowerBound(keys, code)
            while (i < keys.size && keys[i].startsWith(code) && extra.size < 600) {
                if (keys[i] != code) extra.addAll(map[keys[i]]!!)
                i++
            }
            extra.sortByDescending { freq[it] ?: 0L }
            for (e in extra) {
                if (out.size >= 40) break
                out.add(e)
            }
        }
        return out.take(40).map { it to code.length }
    }
}
