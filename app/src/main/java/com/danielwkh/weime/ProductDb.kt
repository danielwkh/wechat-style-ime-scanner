package com.danielwkh.weime

import android.content.Context
import android.net.Uri
import java.io.File

/** 商品庫：條碼 → 商品名。資料存在 App 私有目錄的 products.tsv，匯入後離線可用。 */
object ProductDb {
    @Volatile private var map: Map<String, String>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, "products.tsv")

    /** 去掉前導 0，避免 Excel 把 0123… 存成數字後對不上。 */
    private fun norm(code: String) = code.trim().trimStart('0')

    private fun get(ctx: Context): Map<String, String> {
        map?.let { return it }
        val m = HashMap<String, String>()
        val f = file(ctx)
        if (f.exists()) {
            f.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0) m[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        map = m
        return m
    }

    fun count(ctx: Context): Int = get(ctx).size

    fun lookup(ctx: Context, code: String): String? = get(ctx)[norm(code)]

    /** 匯入 xlsx：第一欄＝條碼（數字），第二欄＝商品名；第一列若是標題會自動略過。 */
    fun import(ctx: Context, uri: Uri): Int {
        val stream = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("無法開啟檔案")
        val rows = stream.use { XlsxReader.read(it) }
        val m = HashMap<String, String>()
        for (r in rows) {
            val code = r.getOrNull(0)?.trim().orEmpty()
            val name = r.getOrNull(1)?.trim().orEmpty()
            val key = norm(code)
            if (key.isNotEmpty() && name.isNotEmpty() && code.all { it.isDigit() }) m[key] = name
        }
        if (m.isEmpty()) throw IllegalStateException("找不到資料：第一欄要是條碼數字，第二欄是商品名")
        file(ctx).bufferedWriter().use { w ->
            for ((k, v) in m) w.write(k + "\t" + v.replace('\t', ' ').replace('\n', ' ') + "\n")
        }
        map = m
        return m.size
    }
}
