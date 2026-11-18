package com.danielwkh.weime

import android.content.Context

/**
 * 關聯詞引擎：完成一個詞之後，依「最後一個中文字」找出可以接著輸入的詞語。
 *
 * 例如輸入「你」→「你們、你好、你家…」，選「你好」後再用尾字「好」→「好像、好看、好友…」，
 * 因此可以一層一層接下去。
 *
 * 詞表來源：assets/related.tsv，每行「錨字 詞 詞 詞…」（該錨字開頭的詞，已依詞頻由高到低排序）。
 * 由 scripts/fetch-ime-data.sh 在編譯時產生：
 *   - 詞條：jieba 詞典（MIT）轉為繁體，避免只有兩個字湊巧相鄰的「你一、你個」等假詞；
 *   - 排序：rime-essay 詞頻（LGPL），讓常用的詞排在前面。
 * 資產不存在時（例如本機未執行腳本）自動退回 Dict 手寫詞庫，功能仍可用。
 */
object RelatedWords {
    /** 每個錨字最多顯示的關聯詞數量。 */
    private const val MAX_PER_ANCHOR = 24

    @Volatile private var tried = false
    @Volatile private var loaded = false

    /** 錨字（單一漢字）→ 以它開頭的詞，已依詞頻排序。 */
    private var index: Map<Char, List<String>> = emptyMap()

    fun isReady() = loaded
    fun hasIndex() = index.isNotEmpty()

    fun load(ctx: Context) {
        if (tried) return
        val app = ctx.applicationContext
        val m = HashMap<Char, MutableList<String>>()
        try {
            app.assets.open("related.tsv").bufferedReader().useLines { seq ->
                for (line in seq) {
                    val s = line.trim()
                    if (s.isEmpty()) continue
                    val anchor = s[0]
                    if (!isCjk(anchor)) continue
                    val parts = s.substring(1).trim()
                    if (parts.isEmpty()) continue
                    val list = m.getOrPut(anchor) { ArrayList() }
                    for (w in parts.split(' ')) {
                        if (w.length < 2 || w == anchor.toString()) continue
                        if (!w.all { isCjk(it) }) continue
                        if (list.size >= MAX_PER_ANCHOR) break
                        if (w in list) continue
                        list.add(w)
                    }
                }
            }
        } catch (_: Exception) {
            // 沒有詞表也不影響：會退回 Dict 手寫詞庫
        }
        index = m
        loaded = true
        tried = true
    }

    private fun isCjk(c: Char): Boolean = c in '\u3400'..'\u9fff'

    /** 詞語裡最後一個中文字，作為接續的錨字；沒有漢字時回傳 null。 */
    fun anchorOf(word: String): Char? {
        for (i in word.length - 1 downTo 0) {
            val c = word[i]
            if (isCjk(c)) return c
        }
        return null
    }

    /**
     * 找出可接在 [word] 後面、應該顯示在候選列的關聯詞。
     *
     * 規則：以詞的**最後一個中文字**為錨字（規格要求），取以它開頭的詞。
     * 若尾字沒有對應詞條，才往前找前一個字墊檔，避免候選列整個空掉。
     * 同一錨字內依詞頻排序（詞表本身已排序），並排除自身與 [exclude]。
     */
    fun related(word: String, exclude: Set<String> = emptySet(), limit: Int = 40): List<String> {
        if (word.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        // 由尾字往前找錨字；第一個有詞的錨字就用它，不把不同錨字的詞混在一起
        for (i in word.length - 1 downTo 0) {
            val c = word[i]
            if (!isCjk(c)) continue
            val list = index[c]
            if (list != null) {
                for (w in list) {
                    if (w == word || w in exclude) continue
                    out.add(w)
                    if (out.size >= limit) break
                }
            }
            if (out.isEmpty()) {
                // 索引沒有這個錨字（詞表較小）→ 退回手寫詞庫
                for ((w, _) in Dict.related(c.toString())) {
                    if (w == word || w in exclude) continue
                    out.add(w)
                    if (out.size >= limit) break
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.toList()
    }
}
