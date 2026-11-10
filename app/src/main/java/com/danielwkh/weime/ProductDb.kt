package com.danielwkh.weime

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.LinkedHashMap
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile

/**
 * 商品庫：條碼 → 商品名稱／原價／會員價／非會員價。資料存在 App 私有目錄的 products.tsv，匯入後離線可用。
 * 舊版只有兩欄的 products.tsv 仍可讀取；匯入 xlsx 時會自動找出條碼、名稱及價格欄位。
 */
object ProductDb {
    data class Product(
        val name: String,
        val price: String = "",
        val memberPrice: String = "",
        val nonMemberPrice: String = ""
    )
    @Volatile private var map: Map<String, Product>? = null
    data class Match(
        val code: String,
        val name: String,
        val price: String = "",
        val memberPrice: String = "",
        val nonMemberPrice: String = ""
    )
    private val matchCache = LinkedHashMap<String, List<Match>>()

    class ImportResult(
        val count: Int, val added: Int, val updated: Int, val skipped: Int,
        val codeLabels: String, val nameLabel: String
    )

    private class Plan(
        val entry: String,
        val codeCols: List<Int>,
        val nameCol: Int,
        val priceCol: Int,
        val memberPriceCol: Int,
        val nonMemberPriceCol: Int,
        val codeLabels: String,
        val nameLabel: String,
        val score: Double
    )

    private val CODE_KEYS = listOf(
        "商品條碼", "商品条码", "產品條碼", "产品条码", "國際條碼", "国际条码", "條形碼", "条形码",
        "條碼", "条码", "條碼值", "条码值", "barcode", "bar code", "ean", "upc", "gtin",
        "商品編碼", "商品编码", "貨品編碼", "货品编码", "國際碼", "国际码"
    )
    private val NAME_KEYS = listOf(
        "商品名稱", "商品名称", "產品名稱", "产品名称", "貨品名稱", "货品名称", "貨物名稱", "货物名称",
        "品項名稱", "品项名称", "品項", "品项", "產品名", "产品名", "商品名", "品名", "貨名", "货名", "名稱", "名称",
        "productname", "itemname", "name", "description", "title", "product", "item"
    )
    private val PRICE_KEYS = listOf(
        "原價", "原价", "售價", "售价", "零售價", "零售价", "單價", "单价", "價格", "价格",
        "price", "retailprice", "sellingprice", "saleprice"
    )
    private val MEMBER_PRICE_KEYS = listOf(
        "會員價", "会员价", "會員價格", "会员价格", "vip價", "vip价", "memberprice", "member_price", "vipprice"
    )
    private val NON_MEMBER_PRICE_KEYS = listOf(
        "非會員價", "非会员价", "非會員價格", "非会员价格", "一般價", "一般价",
        "nonmemberprice", "non_member_price", "regularprice", "normalprice"
    )
    private val CODE_PATTERN = Regex("^(\\d{4,14})(?:-[1-4])?$")

    private fun file(ctx: Context) = File(ctx.filesDir, "products.tsv")

    fun hasDatabase(ctx: Context): Boolean = file(ctx).let { it.isFile && it.length() > 0L }

    /** 移除前導零與明確的 -1/-2/-3/-4 變體尾碼，不刪一般條碼的最後一位。 */
    private fun codeBase(raw: String): String? {
        val match = CODE_PATTERN.matchEntire(raw.trim()) ?: return null
        return match.groupValues[1].trimStart('0')
    }

    private fun isCode(v: String) = codeBase(v) != null
    private fun isTextual(v: String) = v.any { it.isLetter() }

    private fun get(ctx: Context): Map<String, Product> {
        map?.let { return it }
        val m = HashMap<String, Product>()
        val f = file(ctx)
        if (f.exists()) {
            f.forEachLine { line ->
                val fields = line.split('\t', limit = 5)
                if (fields.size >= 2 && fields[0].isNotBlank()) {
                    m[fields[0]] = Product(
                        name = fields.getOrNull(1).orEmpty(),
                        price = fields.getOrNull(2).orEmpty(),
                        memberPrice = fields.getOrNull(3).orEmpty(),
                        nonMemberPrice = fields.getOrNull(4).orEmpty()
                    )
                }
            }
        }
        map = m
        return m
    }

    fun count(ctx: Context): Int = get(ctx).size

    /** 完整碼優先；否則以至少 6 位連續數字做包含匹配，依品名去重供唯一命中或選擇。 */
    fun findMatches(ctx: Context, code: String): List<Match> {
        val rawDigits = code.filter { it.isDigit() }
        if (rawDigits.length < 6) return emptyList()
        synchronized(matchCache) { matchCache[rawDigits]?.let { return it } }
        val m = get(ctx)
        val normalized = (codeBase(code) ?: rawDigits).trimStart('0')
        val exact = m[normalized]
        val result = if (exact != null) {
            listOf(Match(normalized, exact.name, exact.price, exact.memberPrice, exact.nonMemberPrice))
        } else {
            val byName = LinkedHashMap<String, Match>()
            val queries = listOf(rawDigits, normalized).distinct().filter { it.length >= 6 }
            for ((key, product) in m) {
                if (queries.any { key.contains(it) }) {
                    byName.putIfAbsent(
                        product.name,
                        Match(key, product.name, product.price, product.memberPrice, product.nonMemberPrice)
                    )
                }
            }
            byName.values.sortedBy { it.name }
        }
        synchronized(matchCache) {
            if (matchCache.size > 128) matchCache.clear()
            matchCache[rawDigits] = result
        }
        return result
    }

    fun lookup(ctx: Context, code: String): String? = findMatches(ctx, code).singleOrNull()?.name

    /** 取得唯一匹配或使用者指定商品的完整資料，供列印編輯器帶入欄位。 */
    fun lookupProduct(ctx: Context, code: String, selectedName: String? = null): Product? {
        val matches = findMatches(ctx, code)
        val match = if (selectedName == null) matches.singleOrNull()
        else matches.firstOrNull { it.name == selectedName }
        return match?.let { Product(it.name, it.price, it.memberPrice, it.nonMemberPrice) }
    }

    /** 用工作表前幾十列判斷哪些欄是條碼、哪一欄是名稱；找不到就回傳 null。 */
    private fun detect(entry: String, sample: List<List<String>>): Plan? {
        val rows = sample.filter { r -> r.any { it.isNotBlank() } }
        if (rows.size < 2) return null

        // 標題列：前 5 列中，沒有條碼樣式的數字、且至少有 2 格文字的那一列
        var h = -1
        for (i in 0 until minOf(5, rows.size)) {
            val r = rows[i]
            if (r.none { isCode(it.trim()) } && r.count { isTextual(it) } >= 2) { h = i; break }
        }
        val header = if (h >= 0) rows[h].map { it.trim() } else emptyList()
        val data = if (h >= 0) rows.drop(h + 1) else rows
        if (data.isEmpty()) return null
        val ncols = rows.maxOf { it.size }

        fun labelOf(c: Int) = header.getOrNull(c)?.takeIf { it.isNotBlank() } ?: "第${c + 1}欄"

        val explicitCodeCols = ArrayList<Int>()
        val inferredCodeCols = ArrayList<Int>()
        var nameCol = -1
        var nameScore = -1.0
        for (c in 0 until ncols) {
            val v = data.mapNotNull { r -> r.getOrNull(c)?.trim()?.takeIf { it.isNotEmpty() } }
            val label = header.getOrNull(c)?.lowercase() ?: ""
            val codeRatio = if (v.isEmpty()) 0.0 else v.count { isCode(it) }.toDouble() / v.size
            val textRatio = if (v.isEmpty()) 0.0 else v.count { isTextual(it) }.toDouble() / v.size
            val distinct = if (v.isEmpty()) 0.0 else v.toSet().size.toDouble() / v.size
            val avgLen = if (v.isEmpty()) 0.0 else v.map { it.length }.average()

            val headerHit = CODE_KEYS.any { label.contains(it) }
            val isExplicitCode = headerHit && (v.isEmpty() || codeRatio >= 0.5)
            val isInferredCode = !headerHit && v.size >= 3 && codeRatio >= 0.8
            if (isExplicitCode) {
                explicitCodeCols.add(c)
                continue
            }
            if (isInferredCode) {
                inferredCodeCols.add(c)
                continue
            }
            if (v.isNotEmpty() && textRatio >= 0.6) {
                val exact = NAME_KEYS.any { label == it }
                val hit = NAME_KEYS.any { label.contains(it) }
                // 名稱欄：標題吻合優先；內容大多不重複、文字較長（排除「門店名稱」這種全部一樣的欄）
                val score = (if (exact) 200.0 else if (hit) 100.0 else 0.0) + distinct * 60 + minOf(avgLen, 30.0)
                if (score > nameScore) { nameScore = score; nameCol = c }
            }
        }
        val codeCols = if (explicitCodeCols.isNotEmpty()) explicitCodeCols else inferredCodeCols
        if (codeCols.isEmpty() || nameCol < 0) return null
        fun compact(value: String) = value.lowercase().replace(" ", "").replace("_", "")
        fun findHeaderColumn(keys: List<String>, blocked: List<String> = emptyList()): Int {
            return header.indexOfFirst { raw ->
                val label = compact(raw)
                blocked.none { label.contains(compact(it)) } &&
                    keys.any { label.contains(compact(it)) }
            }
        }
        val priceCol = findHeaderColumn(PRICE_KEYS, MEMBER_PRICE_KEYS + NON_MEMBER_PRICE_KEYS)
        val memberPriceCol = findHeaderColumn(MEMBER_PRICE_KEYS)
        val nonMemberPriceCol = findHeaderColumn(NON_MEMBER_PRICE_KEYS)
        return Plan(
            entry, codeCols, nameCol, priceCol, memberPriceCol, nonMemberPriceCol,
            codeCols.joinToString("、") { labelOf(it) }, labelOf(nameCol),
            nameScore + codeCols.size * 10
        )
    }

    fun import(ctx: Context, uri: Uri): ImportResult {
        val tmp = File(ctx.cacheDir, "import.xlsx")
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("無法開啟檔案")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        try {
            val zip = try {
                ZipFile(tmp)
            } catch (e: Exception) {
                throw IllegalStateException("不是有效的 xlsx 檔案（不支援舊版 .xls）")
            }
            return zip.use { z ->
                val shared = XlsxReader.sharedStrings(z)
                var best: Plan? = null
                for (entry in XlsxReader.sheetEntries(z)) {
                    val sample = ArrayList<List<String>>()
                    XlsxReader.forEachRow(z, entry, shared, 80) { sample.add(it) }
                    val plan = detect(entry, sample) ?: continue
                    val b = best
                    if (b == null || plan.score > b.score) best = plan
                }
                val plan = best ?: throw IllegalStateException("找不到商品資料：檔案裡需要有「條碼」和「名稱」類的欄位")

                // 補全模式：保留原有商品庫，條碼和名稱都相同的略過，沒有的新增，條碼相同但名稱不同的更新名稱
                val m = HashMap<String, Product>(get(ctx))
                val seen = HashSet<String>()
                var added = 0
                var updated = 0
                var skipped = 0
                XlsxReader.forEachRow(z, plan.entry, shared) { r ->
                    val name = r.getOrNull(plan.nameCol)?.trim().orEmpty()
                    if (name.isNotEmpty() && isTextual(name)) {
                        val price = r.getOrNull(plan.priceCol)?.trim().orEmpty()
                        val memberPrice = r.getOrNull(plan.memberPriceCol)?.trim().orEmpty()
                        val nonMemberPrice = r.getOrNull(plan.nonMemberPriceCol)?.trim().orEmpty()
                        for (c in plan.codeCols) {
                            val code = r.getOrNull(c)?.trim().orEmpty()
                            val k = codeBase(code)
                            if (k != null) {
                                if (k.isEmpty() || !seen.add(k)) continue   // 同一檔案內重複的條碼只處理第一次
                                val product = Product(name, price, memberPrice, nonMemberPrice)
                                val old = m[k]
                                when {
                                    old == null -> { m[k] = product; added++ }
                                    old == product -> skipped++
                                    else -> { m[k] = product; updated++ }
                                }
                            }
                        }
                    }
                }
                if (m.isEmpty()) throw IllegalStateException("找不到有效的條碼資料")
                val tmpOut = File(ctx.filesDir, "products.tsv.tmp")
                tmpOut.bufferedWriter().use { w ->
                    for ((k, product) in m) {
                        val fields = listOf(product.name, product.price, product.memberPrice, product.nonMemberPrice)
                            .map { it.replace('\t', ' ').replace('\n', ' ') }
                        w.write(k + "\t" + fields.joinToString("\t") + "\n")
                    }
                }
                val target = file(ctx)
                try {
                    Files.move(tmpOut.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tmpOut.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                map = m
                synchronized(matchCache) { matchCache.clear() }
                ImportResult(m.size, added, updated, skipped, plan.codeLabels, plan.nameLabel)
            }
        } finally {
            tmp.delete()
        }
    }
}
