package com.danielwkh.weime

import android.content.Context

/**
 * 速成／倉頡／筆劃引擎。
 * 倉頡碼表：Rime 的 cangjie5.base.dict.yaml（倉頡五代單字，LGPL），速成碼由倉頡碼取「首＋尾」兩碼推出；
 * 筆劃碼表：Rime 的 stroke.dict.yaml（h 橫 s 豎 p 撇 n 點 z 折），由編譯腳本整理成 stroke.tsv；
 * 字頻：rime-essay 的單字字頻（freq.tsv），用來讓常用字排在前面。
 * 這些檔案放在 assets，由 scripts/fetch-ime-data.sh 在編譯時下載。
 */
object CjEngine {
    const val QUICK = 1
    const val CANGJIE = 2
    const val STROKE = 3

    // a–z 對應的倉頡字根
    private const val RADICALS = "日月金木水火土竹戈十大中一弓人心手口尸廿山女田難卜符"
    private val WS = Regex("\\s+")

    @Volatile private var tried = false
    @Volatile private var ready = false
    @Volatile private var strokeReady = false
    @Volatile private var freq: Map<String, Long> = emptyMap()

    private var cj: Map<String, List<String>> = emptyMap()
    private var quick: Map<String, List<String>> = emptyMap()
    private var stroke: Map<String, List<String>> = emptyMap()
    private var cjKeys: Array<String> = emptyArray()
    private var quickKeys: Array<String> = emptyArray()
    private var strokeKeys: Array<String> = emptyArray()
    private val prefixCache = HashMap<String, List<String>>()

    fun isReady(scheme: Int) = if (scheme == STROKE) strokeReady else ready
    fun hasFreq() = freq.isNotEmpty()
    fun freqOf(ch: String): Long = freq[ch] ?: 0L

    fun radical(c: Char): Char = RADICALS[(c.lowercaseChar() - 'a').coerceIn(0, 25)]

    private fun strokeGlyph(c: Char): Char = when (c) {
        'h' -> '一'
        's' -> '丨'
        'p' -> '丿'
        'n' -> '丶'
        'z' -> '乙'
        else -> c
    }

    /** 編輯框裡顯示的組字內容：倉頡／速成顯示字根，筆劃顯示筆劃符號。 */
    fun display(scheme: Int, code: CharSequence): String {
        val sb = StringBuilder()
        for (ch in code) sb.append(if (scheme == STROKE) strokeGlyph(ch) else radical(ch))
        return sb.toString()
    }

    /** 速成最多 2 碼，倉頡最多 5 碼，筆劃最多 30 畫。 */
    fun maxLen(scheme: Int) = when (scheme) {
        QUICK -> 2
        STROKE -> 30
        else -> 5
    }

    @Synchronized
    fun load(ctx: Context): Boolean {
        if (tried) return ready
        val app = ctx.applicationContext
        val f = loadFreq(app)
        freq = f
        loadCangjie(app, f)
        loadStroke(app, f)
        tried = true
        return ready
    }

    private fun loadFreq(app: Context): Map<String, Long> {
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
        return f
    }

    private fun loadCangjie(app: Context, f: Map<String, Long>) {
        val text = try {
            app.assets.open("cangjie5.base.dict.yaml").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return
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
        if (c.isEmpty()) return
        cj = finish(c, f)
        quick = finish(q, f)
        cjKeys = cj.keys.sorted().toTypedArray()
        quickKeys = quick.keys.sorted().toTypedArray()
        ready = true
    }

    private fun loadStroke(app: Context, f: Map<String, Long>) {
        try {
            val m = HashMap<String, MutableList<String>>()
            app.assets.open("stroke.tsv").bufferedReader().useLines { seq ->
                for (line in seq) {
                    val i = line.indexOf('\t')
                    if (i <= 0) continue
                    val code = line.substring(i + 1).trim()
                    if (code.isEmpty()) continue
                    add(m, code, line.substring(0, i))
                }
            }
            if (m.isEmpty()) return
            stroke = finish(m, f)
            strokeKeys = stroke.keys.sorted().toTypedArray()
            strokeReady = true
        } catch (_: Exception) {
            // 沒有筆劃碼表：筆劃輸入法不可用，其他不受影響
        }
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

    /**
     * 回傳候選字（字，用掉的碼數）。先列「剛好吻合」的，再補以這串碼開頭的字，都依字頻排序；
     * 最後把「有字頻的常用字」整體排到「沒有字頻的罕見字」前面，所以第一個候選一定是常用字。
     */
    fun lookup(ctx: Context, scheme: Int, code: String): List<Pair<String, Int>> {
        if (code.isEmpty()) return emptyList()
        if (!tried) load(ctx)
        val map: Map<String, List<String>>
        val keys: Array<String>
        when (scheme) {
            QUICK -> { map = quick; keys = quickKeys }
            STROKE -> { map = stroke; keys = strokeKeys }
            else -> { map = cj; keys = cjKeys }
        }
        if (map.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        map[code]?.let { out.addAll(it) }
        val ck = "$scheme:$code"
        val extra = synchronized(prefixCache) { prefixCache[ck] } ?: run {
            val l = ArrayList<String>()
            var i = lowerBound(keys, code)
            while (i < keys.size && keys[i].startsWith(code)) {
                if (keys[i] != code) l.addAll(map[keys[i]]!!)
                i++
            }
            l.sortByDescending { freq[it] ?: 0L }
            val top = l.take(80)
            synchronized(prefixCache) {
                if (prefixCache.size > 4000) prefixCache.clear()
                prefixCache[ck] = top
            }
            top
        }
        out.addAll(extra)

        var list: List<String> = out.toList()
        if (hasFreq()) {
            val (common, rare) = list.partition { (freq[it] ?: 0L) > 0L }
            list = common + rare
        }
        return list.take(40).map { it to code.length }
    }
}
