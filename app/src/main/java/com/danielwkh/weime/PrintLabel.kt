package com.danielwkh.weime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 支援的標籤機型：兩種實物尺寸（中 70×40 / 大 180×100）。
 *
 * 尺寸與 dpi 直接決定列印點陣的像素數：70×40@203dpi=559×320，@300dpi=827×472，
 * 180×100@203dpi=1439×799。預覽與列印共用同一套 LabelGeometry → LabelPainter，
 * 因此預覽即所印。
 */
enum class LabelDevice(
    val title: String,
    val widthMm: Float,
    val heightMm: Float,
    val dpi: Int,
) {
    P1("中標籤 · 70 × 40 mm · 203 dpi", 70f, 40f, 203),
    P1P("中標籤 · 70 × 40 mm · 300 dpi", 70f, 40f, 300),
    DP40("大標籤 · 180 × 100 mm · 203 dpi", 180f, 100f, 203);

    val aspect: Float get() = widthMm / heightMm
    val pxPerMm: Float get() = dpi / 25.4f
    val bitmapWidth: Int get() = (widthMm * pxPerMm).roundToInt().coerceAtLeast(8)
    val bitmapHeight: Int get() = (heightMm * pxPerMm).roundToInt().coerceAtLeast(8)

    companion object {
        fun of(name: String?): LabelDevice = values().firstOrNull { it.name == name } ?: P1
    }
}

/** 標籤上的可編輯元素（點選、拖曳、縮放、選取框）。 */
enum class LabelElement(val title: String) {
    NAME("商品名"),
    BARCODE("條碼"),
    PRICE("價格"),
}

/** 單一元素的位移／縮放／旋轉與樣式；位移單位為標籤 mm。 */
class ElementStyle(
    var dxMm: Float = 0f,
    var dyMm: Float = 0f,
    var scale: Float = 1f,
    var rotation: Int = 0,
    var invert: Boolean = false,
    var textScale: Float = 1f,
    var bold: Boolean = false,
) {
    fun copy() = ElementStyle(dxMm, dyMm, scale, rotation, invert, textScale, bold)
    fun reset() { dxMm = 0f; dyMm = 0f; scale = 1f; rotation = 0; invert = false; textScale = 1f; bold = false }
}

/** 要印在標籤上的內容與尺寸設定（滑桿數值）。 */
class LabelData {
    var name = ""
    var barcode = ""
    var price = ""          // 單價時使用（已映射為原價／非會員價）
    var member = ""         // 會員價（有值時顯示會員價版）
    var nonMember = ""      // 非會員價／原價
    var showMember = false  // true = 會員價版（右側兩排），false = 無會員價版（右側單一大價）
    var nameSize = 28
    var priceSize = 58
    var barcodeLabelSize = 17
    var barcodeHeight = 58
    var barcodeScale = 90
    var barcodeType = BarcodeRenderer.TYPE_AUTO
}

/**
 * 版面的單一計算來源（mm 座標系）。
 *
 * 對齊截圖的 2 款模板 × 2 種尺寸（見 Preview/IMG_9762~9763）：
 *
 *  共同：品名在頂部居中，可 1–2 行；下方左右分區。
 *  左區（約 35% 寬）：條碼條紋 + 下方數字（置中）。
 *  右區（約 62% 寬）：價格。
 *    - 無會員價：右區單一巨大價格「$  38」，$ 符小、數字特大，整塊在右區置中偏右。
 *    - 會員價版：右區上下兩塊，上塊黑底白字「Member $ 28」，下塊白底黑字「Price  $ 35」。
 *
 *  兩種機型僅差在整體 w/h 比例（1.75 vs 1.80），框用相對比例因此自動適配。
 */
class LabelGeometry(val device: LabelDevice, val data: LabelData) {
    val w: Float get() = device.widthMm
    val h: Float get() = device.heightMm

    fun fontMm(slider: Int): Float = h * slider / 160f

    fun base(element: LabelElement): RectF = when (element) {
        LabelElement.NAME ->
            // 頂部通欄：0.02–0.30 高，左右留 2.5% 邊距，高度足夠兩行
            RectF(w * 0.025f, h * 0.02f, w * 0.975f, h * 0.30f)

        LabelElement.BARCODE ->
            // 左區固定：x 0.03–0.38w，頂部對齊 0.32h；條碼寬度由 barcodeScale(40–100) 在此區內縮放
            barcodeRect(w * 0.03f, w * 0.38f, h * 0.32f)

        LabelElement.PRICE ->
            if (data.showMember) {
                // 會員價版：右區整塊，上黑下白各佔一半
                RectF(w * 0.40f, h * 0.32f, w * 0.97f, h * 0.97f)
            } else {
                // 無會員價：右區單塊巨大價格
                RectF(w * 0.40f, h * 0.30f, w * 0.97f, h * 0.97f)
            }
    }

    /**
     * 條碼框（含下方數字）。[leftMm]–[rightMm] 為可用寬度，實際條碼寬度 = 可用寬 × barcodeScale%。
     */
    private fun barcodeRect(leftMm: Float, rightMm: Float, topRatio: Float): RectF {
        val availW = rightMm - leftMm
        val bw = (availW * data.barcodeScale / 100f).coerceIn(availW * 0.40f, availW)
        val top = topRatio
        val digits = fontMm(data.barcodeLabelSize)
        val gap = h * 0.018f
        val bottom = top + min(h * data.barcodeHeight / 100f, h * 0.38f) + gap + digits
        // 不超過標籤底
        val clampedBottom = min(bottom, h * 0.97f)
        return RectF(leftMm, top, leftMm + bw, clampedBottom)
    }

    /** 條碼條紋區域（不含下方數字）。 */
    fun barcodeStripes(): RectF {
        val b = base(LabelElement.BARCODE)
        val digits = fontMm(data.barcodeLabelSize)
        val gap = h * 0.018f
        return RectF(b.left, b.top, b.right, max(b.top + h * 0.05f, b.bottom - digits - gap))
    }

    /** 價格會員價版的上下兩段（各佔一半）。 */
    fun priceHalves(): Pair<RectF, RectF> {
        val b = base(LabelElement.PRICE)
        val mid = (b.top + b.bottom) / 2f
        return RectF(b.left, b.top, b.right, mid) to RectF(b.left, mid, b.right, b.bottom)
    }

    fun corners(base: RectF, st: ElementStyle): Array<PointF> {
        val cx = (base.left + base.right) / 2f
        val cy = (base.top + base.bottom) / 2f
        val rad = Math.toRadians(st.rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val src = arrayOf(base.left to base.top, base.right to base.top, base.right to base.bottom, base.left to base.bottom)
        return Array(4) { i ->
            val (px, py) = src[i]
            val x = (px - cx) * st.scale
            val y = (py - cy) * st.scale
            PointF(cx + x * c - y * s + st.dxMm, cy + x * s + y * c + st.dyMm)
        }
    }

    fun clamp(st: ElementStyle, base: RectF) {
        val pts = corners(base, st)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) { minX = min(minX, p.x); maxX = max(maxX, p.x); minY = min(minY, p.y); maxY = max(maxY, p.y) }
        if (maxX - minX <= w) { if (minX < 0f) st.dxMm += -minX; if (maxX > w) st.dxMm -= (maxX - w) }
        if (maxY - minY <= h) { if (minY < 0f) st.dyMm += -minY; if (maxY > h) st.dyMm -= (maxY - h) }
    }

    fun toLocal(xMm: Float, yMm: Float, base: RectF, st: ElementStyle): PointF {
        val cx = (base.left + base.right) / 2f; val cy = (base.top + base.bottom) / 2f
        var x = xMm - st.dxMm - cx; var y = yMm - st.dyMm - cy
        val rad = Math.toRadians(-st.rotation.toDouble()); val c = cos(rad).toFloat(); val s = sin(rad).toFloat()
        val rx = x * c - y * s; val ry = x * s + y * c
        val sc = if (st.scale <= 0.01f) 0.01f else st.scale
        return PointF(rx / sc + cx, ry / sc + cy)
    }

    fun hitTest(styles: Map<LabelElement, ElementStyle>, xMm: Float, yMm: Float): LabelElement? {
        for (e in LabelElement.values().reversed()) {
            val base = base(e); val p = toLocal(xMm, yMm, base, styles.getValue(e))
            if (base.contains(p.x, p.y)) return e
        }
        return null
    }
}

/** 預覽與列印共用的繪製器：預覽(pxPerMm=螢幕) 與列印(pxPerMm=dpi/25.4) 結果一致。 */
class LabelPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas, geo: LabelGeometry, styles: Map<LabelElement, ElementStyle>,
        pxPerMm: Float, ox: Float, oy: Float, selected: LabelElement?, showSelection: Boolean,
    ) {
        for (e in LabelElement.values()) drawElement(canvas, geo, e, styles.getValue(e), pxPerMm, ox, oy)
        if (showSelection && selected != null) drawSelection(canvas, geo, styles.getValue(selected), selected, pxPerMm, ox, oy)
    }

    private fun drawElement(canvas: Canvas, geo: LabelGeometry, element: LabelElement, st: ElementStyle, pxPerMm: Float, ox: Float, oy: Float) {
        val base = geo.base(element)
        val cx = (base.left + base.right) / 2f; val cy = (base.top + base.bottom) / 2f
        canvas.save()
        canvas.translate(ox + st.dxMm * pxPerMm, oy + st.dyMm * pxPerMm)
        canvas.rotate(st.rotation.toFloat(), cx * pxPerMm, cy * pxPerMm)
        canvas.scale(st.scale, st.scale, cx * pxPerMm, cy * pxPerMm)
        val ink = if (st.invert) Color.WHITE else Color.BLACK
        if (st.invert) {
            paint.color = Color.BLACK; paint.style = Paint.Style.FILL
            canvas.drawRect(base.left * pxPerMm, base.top * pxPerMm, base.right * pxPerMm, base.bottom * pxPerMm, paint)
        }
        when (element) {
            LabelElement.NAME -> drawName(canvas, geo, st, ink, pxPerMm, base)
            LabelElement.BARCODE -> drawBarcode(canvas, geo, st, ink, pxPerMm)
            LabelElement.PRICE -> drawPrice(canvas, geo, st, ink, pxPerMm, base)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------- 品名（支援換行，兩行置中）

    private fun drawName(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float, base: RectF) {
        val raw = geo.data.name.ifBlank { "商品名稱" }
        // 以 \n 為優先；沒有則嘗試按寬度折行（避免超長單行被截掉）
        val rows = layoutName(raw, base.width() * pxPerMm, geo, st, pxPerMm)
        if (rows.isEmpty()) return

        paint.style = Paint.Style.FILL; paint.color = ink; paint.textAlign = Paint.Align.CENTER
        paint.typeface = if (st.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // 依框高與行數計算最大字級（兩行時每行高度減半）
        val maxBoxH = (base.bottom - base.top) * pxPerMm * 0.96f
        var size = geo.fontMm(geo.data.nameSize) * st.textScale * pxPerMm
        paint.textSize = size
        val lineH = paint.fontMetrics.descent - paint.fontMetrics.ascent
        val needH = lineH * rows.size + (if (rows.size > 1) lineH * 0.15f else 0f)
        if (needH > maxBoxH && needH > 0f) { size *= maxBoxH / needH; paint.textSize = size }

        // 垂直置中多行
        paint.textSize = size
        val fm = paint.fontMetrics
        val lh = fm.descent - fm.ascent
        val gap = lh * 0.15f
        val totalH = lh * rows.size + gap * (rows.size - 1)
        val top = (base.top + base.bottom) / 2f * pxPerMm - totalH / 2f - fm.ascent
        val cx = (base.left + base.right) / 2f * pxPerMm
        for ((i, line) in rows.withIndex()) {
            val y = top + i * (lh + gap)
            canvas.drawText(line, cx, y, paint)
        }
    }

    /** 將品名排成 1–2 行；已含 \n 時直接分段，否則按框寬折行。 */
    private fun layoutName(raw: String, maxW: Float, geo: LabelGeometry, st: ElementStyle, pxPerMm: Float): List<String> {
        val size = geo.fontMm(geo.data.nameSize) * st.textScale * pxPerMm
        paint.textSize = size
        // 明確換行優先
        if (raw.contains("\n")) {
            return raw.split("\n").take(2).map { fit(it.trim(), maxW) }
        }
        if (paint.measureText(raw) <= maxW) return listOf(raw)
        // 依字元折行：找最接近中點且不截斷詞的位置
        val chars = raw.toList()
        var best = chars.size / 2
        // 優先在空格或符號後斷開
        for (i in chars.indices) {
            if (chars[i] == ' ' || chars[i] == '·' || chars[i] == '-' || chars[i] == '／') {
                if (paint.measureText(raw.substring(0, i + 1)) <= maxW) best = i + 1
            }
        }
        // 若空格斷開仍超寬，改為按寬度二分搜尋
        var lo = 1; var hi = raw.length - 1; var cut = best
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (paint.measureText(raw.substring(0, mid)) <= maxW) { cut = mid; lo = mid + 1 } else hi = mid - 1
        }
        // 保證第二行也能放下；放不下時縮短第一行
        while (cut > 1 && paint.measureText(raw.substring(cut).trim()) > maxW) cut--
        if (cut <= 0 || cut >= raw.length) return listOf(fit(raw, maxW))
        return listOf(raw.substring(0, cut).trim(), fit(raw.substring(cut).trim(), maxW))
    }

    // ---------------------------------------------------------------- 條碼（黑色條 + 下方後六碼置中）

    private fun drawBarcode(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float) {
        val data = geo.data
        val stripes = geo.barcodeStripes()
        val encoded = BarcodeRenderer.encode(data.barcode, data.barcodeType)
        paint.style = Paint.Style.FILL; paint.color = ink; paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
        if (encoded == null) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = (0.25f * pxPerMm).coerceAtLeast(1f)
            paint.color = if (st.invert) Color.WHITE else Color.rgb(190, 45, 45)
            canvas.drawRect(stripes.left * pxPerMm, stripes.top * pxPerMm, stripes.right * pxPerMm, stripes.bottom * pxPerMm, paint)
            paint.style = Paint.Style.FILL; paint.textSize = geo.fontMm(data.barcodeLabelSize) * st.textScale * pxPerMm
            canvas.drawText(if (data.barcode.isBlank()) "尚未輸入條碼" else "條碼格式無效",
                (stripes.left + stripes.right) / 2f * pxPerMm, (stripes.top + stripes.bottom) / 2f * pxPerMm, paint)
            return
        }
        val left = stripes.left * pxPerMm; val top = stripes.top * pxPerMm
        val wPx = (stripes.right - stripes.left) * pxPerMm; val hPx = (stripes.bottom - stripes.top) * pxPerMm
        val module = wPx / encoded.bits.length
        for (i in encoded.bits.indices) if (encoded.bits[i] == '1')
            canvas.drawRect(left + i * module, top, left + (i + 1) * module, top + hPx, paint)
        val digits = encoded.display.takeLast(6)
        if (digits.isNotEmpty()) {
            paint.textSize = geo.fontMm(data.barcodeLabelSize) * st.textScale * pxPerMm; paint.color = ink
            // 數字基線：條紋底 + 小間隙；用 ascent 偏移讓文字上方貼近條紋
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(digits, left + wPx / 2f, (stripes.bottom * pxPerMm) + paint.fontMetrics.descent * 0.1f + geo.h * 0.018f * pxPerMm, paint)
        }
    }

    // ---------------------------------------------------------------- 價格

    private fun drawPrice(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float, base: RectF) {
        val data = geo.data
        paint.style = Paint.Style.FILL; paint.color = ink; paint.typeface = Typeface.DEFAULT_BOLD
        val boxHPx = (base.bottom - base.top) * pxPerMm
        val boxWPx = base.width() * pxPerMm
        val leftPx = base.left * pxPerMm; val rightPx = base.right * pxPerMm
        fun money(v: String) = if (v.isBlank()) "—" else v.trim()

        fun fitSize(requested: Float, maxH: Float, sample: String, maxW: Float): Float {
            var size = requested; paint.textSize = size
            val neededH = paint.fontMetrics.descent - paint.fontMetrics.ascent
            if (neededH > maxH && neededH > 0f) size *= maxH / neededH
            paint.textSize = size
            val w = paint.measureText(sample)
            if (w > maxW && w > 0f) size *= maxW / w
            return size
        }

        if (data.showMember) {
            // 會員價版：上黑下白，截圖 Member $28 黑底、Price $60 白底
            val (upper, lower) = geo.priceHalves()
            val upperTop = upper.top * pxPerMm; val upperH = upper.height() * pxPerMm
            val lowerTop = lower.top * pxPerMm; val lowerH = lower.height() * pxPerMm

            // 上段：黑色實心底 + 白字
            paint.style = Paint.Style.FILL; paint.color = Color.BLACK
            canvas.drawRect(upper.left * pxPerMm, upper.top * pxPerMm, upper.right * pxPerMm, upper.bottom * pxPerMm, paint)
            val mMoney = money(data.member)
            val nMoney = money(data.nonMember)
            // 上段 Member $ xx：Member 小字靠左，$ + 數字靠右，皆白字
            val mSize = fitSize(geo.fontMm(data.priceSize) * st.textScale * pxPerMm * 0.82f, upperH * 0.72f, "$ $mMoney", boxWPx * 0.96f)
            drawMemberRow(canvas, "Member", mMoney, upperTop, upperH, mSize, leftPx, rightPx, Color.WHITE)
            // 下段：白底黑字 Price $ xx
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            // 下段已是白底（整體標籤白底），不需要再塗白，僅避免上段 invert 影響
            val nSize = fitSize(geo.fontMm(data.priceSize) * st.textScale * pxPerMm * 0.82f, lowerH * 0.72f, "$ $nMoney", boxWPx * 0.96f)
            drawMemberRow(canvas, "Price", nMoney, lowerTop, lowerH, nSize, leftPx, rightPx, ink)
        } else {
            // 無會員價：右區單一巨大價格，$ 符小、數字超大，整組在右區置中
            val priceText = money(data.price)
            val priceSample = "$ $priceText"
            val big = fitSize(geo.fontMm(data.priceSize) * st.textScale * pxPerMm, boxHPx * 0.88f, priceSample, boxWPx * 0.98f)
            // 將 $ 與數字分開繪製，讓 $ 明顯小於數字（截圖 $ 約為數字高度的 40–45%）
            val dollarSize = big * 0.42f
            paint.textSize = big; paint.textAlign = Paint.Align.RIGHT
            val fm = paint.fontMetrics
            val cy = (base.top + base.bottom) / 2f * pxPerMm
            val baseline = cy - (fm.ascent + fm.descent) / 2f
            // 先量 $ + 空隙寬度，決定兩段的位置
            paint.textSize = dollarSize
            val dollarW = paint.measureText("$ ")
            paint.textSize = big
            val numberW = paint.measureText(priceText)
            val totalW = dollarW + numberW
            val startX = rightPx - totalW
            // $（小字，基線對齊數字的基線，視覺上靠下）
            paint.textSize = dollarSize; paint.textAlign = Paint.Align.LEFT
            // 小 $ 的基線與大數字基線一致（已共用 baseline），但 $ 本身字高較矮，視覺上自然偏下對齊
            canvas.drawText("$", startX, baseline, paint)
            // 數字（大字）
            paint.textSize = big
            canvas.drawText(priceText, startX + dollarW, baseline, paint)
        }
    }

    /** 會員價版的一列：左側標籤小字，右側 $ 小 + 數字大。上下兩列共用。 */
    private fun drawMemberRow(
        canvas: Canvas, label: String, value: String,
        topPx: Float, rowHPx: Float, big: Float,
        leftPx: Float, rightPx: Float, ink: Int,
    ) {
        paint.color = ink; paint.style = Paint.Style.FILL
        // 標籤字級約為價格的 32%
        val small = big * 0.34f
        val fmBig = run { paint.textSize = big; paint.fontMetrics }
        val fmSmall = run { paint.textSize = small; paint.fontMetrics }
        // 兩行文字基線對齊：在列高內垂直置中
        val rowCy = topPx + rowHPx / 2f
        val bigBaseline = rowCy - (fmBig.ascent + fmBig.descent) / 2f
        val smallBaseline = rowCy - (fmSmall.ascent + fmSmall.descent) / 2f

        // 右側：$ + 數字（$ 小 42%）
        val dollarSize = big * 0.42f
        paint.textSize = big; val numW = paint.measureText(value)
        paint.textSize = dollarSize; val dolW = paint.measureText("$ ")
        val totalW = dolW + numW
        val numStart = rightPx - totalW

        paint.textSize = small; paint.textAlign = Paint.Align.LEFT
        canvas.drawText(label, leftPx + big * 0.04f, smallBaseline, paint)

        paint.textSize = dollarSize; paint.textAlign = Paint.Align.LEFT
        canvas.drawText("$", numStart, bigBaseline, paint)
        paint.textSize = big
        canvas.drawText(value, numStart + dolW, bigBaseline, paint)
    }

    private fun drawSelection(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, element: LabelElement, pxPerMm: Float, ox: Float, oy: Float) {
        val base = geo.base(element); val cx = (base.left + base.right) / 2f; val cy = (base.top + base.bottom) / 2f
        canvas.save()
        canvas.translate(ox + st.dxMm * pxPerMm, oy + st.dyMm * pxPerMm)
        canvas.rotate(st.rotation.toFloat(), cx * pxPerMm, cy * pxPerMm)
        canvas.scale(st.scale, st.scale, cx * pxPerMm, cy * pxPerMm)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = (0.3f * pxPerMm).coerceIn(1f, 4f); paint.color = Color.rgb(40, 120, 230)
        canvas.drawRect(base.left * pxPerMm, base.top * pxPerMm, base.right * pxPerMm, base.bottom * pxPerMm, paint)
        paint.style = Paint.Style.FILL; val r = paint.strokeWidth * 1.6f
        for (p in arrayOf(base.left to base.top, base.right to base.top, base.right to base.bottom, base.left to base.bottom))
            canvas.drawCircle(p.first * pxPerMm, p.second * pxPerMm, r, paint)
        canvas.restore()
    }

    private fun fit(text: String, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        var lo = 0; var hi = text.length
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (paint.measureText(text.substring(0, mid) + "…") <= maxWidth) lo = mid else hi = mid - 1 }
        return if (lo <= 0) "…" else text.substring(0, lo) + "…"
    }
}
