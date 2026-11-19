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

enum class LabelDevice(val title: String, val widthMm: Float, val heightMm: Float, val dpi: Int) {
    P1("中標籤 · 70 × 40 mm · 203 dpi", 70f, 40f, 203),
    P1P("中標籤 · 70 × 40 mm · 300 dpi", 70f, 40f, 300),
    DP40("大標籤 · 180 × 100 mm · 203 dpi", 180f, 100f, 203);
    val aspect: Float get() = widthMm / heightMm
    val pxPerMm: Float get() = dpi / 25.4f
    val bitmapWidth: Int get() = (widthMm * pxPerMm).roundToInt().coerceAtLeast(8)
    val bitmapHeight: Int get() = (heightMm * pxPerMm).roundToInt().coerceAtLeast(8)
    companion object { fun of(name: String?): LabelDevice = values().firstOrNull { it.name == name } ?: P1 }
}

enum class LabelElement(val title: String) { NAME("商品名"), BARCODE("條碼"), PRICE("價格") }

class ElementStyle(
    var dxMm: Float = 0f, var dyMm: Float = 0f, var scale: Float = 1f,
    var rotation: Int = 0, var invert: Boolean = false,
    var textScale: Float = 1f, var bold: Boolean = false,
) {
    fun copy() = ElementStyle(dxMm, dyMm, scale, rotation, invert, textScale, bold)
    fun reset() { dxMm=0f; dyMm=0f; scale=1f; rotation=0; invert=false; textScale=1f; bold=false }
}

class LabelData {
    var name = ""; var barcode = ""; var price = ""; var member = ""; var nonMember = ""
    var showMember = false
    var nameSize = 28; var priceSize = 58; var barcodeLabelSize = 17
    var barcodeHeight = 58; var barcodeScale = 90; var barcodeType = BarcodeRenderer.TYPE_AUTO
}

class LabelGeometry(val device: LabelDevice, val data: LabelData) {
    val w: Float get() = device.widthMm; val h: Float get() = device.heightMm
    fun fontMm(slider: Int): Float = h * slider / 160f

    fun base(element: LabelElement): RectF = when (element) {
        LabelElement.NAME -> RectF(w*0.025f, h*0.03f, w*0.975f, h*0.30f)
        LabelElement.BARCODE -> barcodeRect(w*0.03f, w*0.38f, h*0.32f)
        LabelElement.PRICE -> if (data.showMember) RectF(w*0.40f, h*0.32f, w*0.97f, h*0.965f)
        else RectF(w*0.40f, h*0.30f, w*0.97f, h*0.965f)
    }

    private fun barcodeRect(leftMm: Float, rightMm: Float, topMm: Float): RectF {
        val availW = rightMm - leftMm
        val bw = (availW * data.barcodeScale / 100f).coerceIn(availW*0.40f, availW)
        val digits = fontMm(data.barcodeLabelSize)
        val gap = h*0.025f // 條紋與數字的固定間隙（不重疊的關鍵）
        val stripeH = min(h * data.barcodeHeight / 100f, h*0.38f)
        val bottom = topMm + stripeH + gap + digits
        return RectF(leftMm, topMm, leftMm + bw, min(bottom, h*0.965f))
    }

    // 條紋區域：b.top 到 b.bottom - gap - digits
    fun barcodeStripes(): RectF {
        val b = base(LabelElement.BARCODE)
        val digits = fontMm(data.barcodeLabelSize); val gap = h*0.025f
        return RectF(b.left, b.top, b.right, max(b.top + h*0.06f, b.bottom - gap - digits))
    }
    // 數字區域的基線參考：條紋底下方 gap 處（不覆蓋條紋）
    fun barcodeDigitsBaseline(): Float {
        val s = barcodeStripes()
        return s.bottom + h*0.025f
    }

    fun priceHalves(): Pair<RectF, RectF> {
        val b = base(LabelElement.PRICE); val mid = (b.top + b.bottom)/2f
        return RectF(b.left, b.top, b.right, mid) to RectF(b.left, mid, b.right, b.bottom)
    }

    fun corners(base: RectF, st: ElementStyle): Array<PointF> {
        val cx=(base.left+base.right)/2f; val cy=(base.top+base.bottom)/2f
        val rad=Math.toRadians(st.rotation.toDouble()); val c=cos(rad).toFloat(); val s=sin(rad).toFloat()
        val src=arrayOf(base.left to base.top, base.right to base.top, base.right to base.bottom, base.left to base.bottom)
        return Array(4){ i -> val (px,py)=src[i]; val x=(px-cx)*st.scale; val y=(py-cy)*st.scale
            PointF(cx+x*c-y*s+st.dxMm, cy+x*s+y*c+st.dyMm) }
    }
    fun clamp(st: ElementStyle, base: RectF){
        val pts=corners(base,st); var minX=Float.MAX_VALUE; var maxX=-Float.MAX_VALUE; var minY=Float.MAX_VALUE; var maxY=-Float.MAX_VALUE
        for(p in pts){ minX=min(minX,p.x); maxX=max(maxX,p.x); minY=min(minY,p.y); maxY=max(maxY,p.y) }
        if(maxX-minX<=w){ if(minX<0f) st.dxMm+=-minX; if(maxX>w) st.dxMm-=(maxX-w) }
        if(maxY-minY<=h){ if(minY<0f) st.dyMm+=-minY; if(maxY>h) st.dyMm-=(maxY-h) }
    }
    fun toLocal(xMm: Float, yMm: Float, base: RectF, st: ElementStyle): PointF {
        val cx=(base.left+base.right)/2f; val cy=(base.top+base.bottom)/2f
        var x=xMm-st.dxMm-cx; var y=yMm-st.dyMm-cy; val rad=Math.toRadians(-st.rotation.toDouble())
        val c=cos(rad).toFloat(); val s=sin(rad).toFloat(); val rx=x*c-y*s; val ry=x*s+y*c
        val sc=if(st.scale<=0.01f)0.01f else st.scale; return PointF(rx/sc+cx, ry/sc+cy)
    }
    fun hitTest(styles: Map<LabelElement, ElementStyle>, xMm: Float, yMm: Float): LabelElement? {
        // 使用變換後的四邊形做點內判定，支援旋轉/縮放後的精準選取
        for(e in LabelElement.values().reversed()){
            val b=base(e)
            val st = styles[e] ?: ElementStyle()
            val pts = corners(b, st)
            if (pointInQuad(xMm, yMm, pts)) return e
            // 放寬 1mm 觸控容差，方便手指點選
            val expanded = expandRect(b, 1.2f)
            val pts2 = corners(expanded, st)
            if (pointInQuad(xMm, yMm, pts2)) return e
        }
        return null
    }
    private fun expandRect(r: RectF, extraMm: Float): RectF = RectF(r.left - extraMm, r.top - extraMm, r.right + extraMm, r.bottom + extraMm)
    private fun pointInQuad(x: Float, y: Float, pts: Array<PointF>): Boolean {
        // 凸四邊形 winding：點在四條邊同側即為內部
        var sign = 0
        for(i in pts.indices){
            val a=pts[i]; val b=pts[(i+1)%pts.size]
            val cross = (b.x - a.x)*(y - a.y) - (b.y - a.y)*(x - a.x)
            val s = when { cross > 1e-6f -> 1; cross < -1e-6f -> -1; else -> 0 }
            if(s==0) continue
            if(sign==0) sign=s else if(sign!=s) return false
        }
        return true
    }
}

class LabelPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    fun draw(canvas: Canvas, geo: LabelGeometry, styles: Map<LabelElement, ElementStyle>, pxPerMm: Float, ox: Float, oy: Float, selected: LabelElement?, showSelection: Boolean){
        for(e in LabelElement.values()) drawElement(canvas, geo, e, styles.getValue(e), pxPerMm, ox, oy)
        if(showSelection && selected!=null) drawSelection(canvas, geo, styles.getValue(selected), selected, pxPerMm, ox, oy)
    }
    private fun drawElement(canvas: Canvas, geo: LabelGeometry, element: LabelElement, st: ElementStyle, pxPerMm: Float, ox: Float, oy: Float){
        val base=geo.base(element); val cx=(base.left+base.right)/2f; val cy=(base.top+base.bottom)/2f
        canvas.save(); canvas.translate(ox+st.dxMm*pxPerMm, oy+st.dyMm*pxPerMm)
        canvas.rotate(st.rotation.toFloat(), cx*pxPerMm, cy*pxPerMm); canvas.scale(st.scale, st.scale, cx*pxPerMm, cy*pxPerMm)
        val ink=if(st.invert) Color.WHITE else Color.BLACK
        if(st.invert){ paint.color=Color.BLACK; paint.style=Paint.Style.FILL; canvas.drawRect(base.left*pxPerMm, base.top*pxPerMm, base.right*pxPerMm, base.bottom*pxPerMm, paint) }
        when(element){ LabelElement.NAME->drawName(canvas,geo,st,ink,pxPerMm,base); LabelElement.BARCODE->drawBarcode(canvas,geo,st,ink,pxPerMm); LabelElement.PRICE->drawPrice(canvas,geo,st,ink,pxPerMm,base) }
        canvas.restore()
    }

    private fun drawName(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float, base: RectF){
        val raw=geo.data.name.ifBlank{"商品名稱"}; val rows=layoutName(raw, base.width()*pxPerMm, geo, st, pxPerMm); if(rows.isEmpty()) return
        paint.style=Paint.Style.FILL; paint.color=ink; paint.textAlign=Paint.Align.CENTER; paint.typeface=if(st.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val maxBoxH=(base.bottom-base.top)*pxPerMm*0.96f; var size=geo.fontMm(geo.data.nameSize)*st.textScale*pxPerMm
        paint.textSize=size; val lineH=paint.fontMetrics.descent-paint.fontMetrics.ascent
        val needH=lineH*rows.size + if(rows.size>1) lineH*0.15f else 0f
        if(needH>maxBoxH && needH>0f){ size*=maxBoxH/needH; paint.textSize=size }
        paint.textSize=size; val fm=paint.fontMetrics; val lh=fm.descent-fm.ascent; val gap=lh*0.15f
        val totalH=lh*rows.size + gap*(rows.size-1); val top=(base.top+base.bottom)/2f*pxPerMm - totalH/2f - fm.ascent
        val cx=(base.left+base.right)/2f*pxPerMm
        for((i,line) in rows.withIndex()) canvas.drawText(line, cx, top + i*(lh+gap), paint)
    }
    private fun layoutName(raw: String, maxW: Float, geo: LabelGeometry, st: ElementStyle, pxPerMm: Float): List<String>{
        val size=geo.fontMm(geo.data.nameSize)*st.textScale*pxPerMm; paint.textSize=size
        if(raw.contains("\n")) return raw.split("\n").take(2).map{ fit(it.trim(), maxW) }
        if(paint.measureText(raw)<=maxW) return listOf(raw)
        val chars=raw.toList(); var best=chars.size/2
        for(i in chars.indices) if(chars[i]==' '||chars[i]=='·'||chars[i]=='-'||chars[i]=='／'){ if(paint.measureText(raw.substring(0,i+1))<=maxW) best=i+1 }
        var lo=1; var hi=raw.length-1; var cut=best
        while(lo<=hi){ val mid=(lo+hi)/2; if(paint.measureText(raw.substring(0,mid))<=maxW){ cut=mid; lo=mid+1 } else hi=mid-1 }
        while(cut>1 && paint.measureText(raw.substring(cut).trim())>maxW) cut--
        if(cut<=0 || cut>=raw.length) return listOf(fit(raw,maxW))
        return listOf(raw.substring(0,cut).trim(), fit(raw.substring(cut).trim(), maxW))
    }

    private fun drawBarcode(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float){
        val data=geo.data; val stripes=geo.barcodeStripes(); val digitsBaseline=geo.barcodeDigitsBaseline()
        val encoded=BarcodeRenderer.encode(data.barcode, data.barcodeType)
        paint.style=Paint.Style.FILL; paint.color=ink; paint.textAlign=Paint.Align.CENTER; paint.typeface=Typeface.DEFAULT
        if(encoded==null){
            paint.style=Paint.Style.STROKE; paint.strokeWidth=(0.25f*pxPerMm).coerceAtLeast(1f); paint.color=if(st.invert) Color.WHITE else Color.rgb(190,45,45)
            canvas.drawRect(stripes.left*pxPerMm, stripes.top*pxPerMm, stripes.right*pxPerMm, stripes.bottom*pxPerMm, paint)
            paint.style=Paint.Style.FILL; paint.textSize=geo.fontMm(data.barcodeLabelSize)*st.textScale*pxPerMm
            canvas.drawText(if(data.barcode.isBlank())"尚未輸入條碼" else "條碼格式無效", (stripes.left+stripes.right)/2f*pxPerMm, (stripes.top+stripes.bottom)/2f*pxPerMm, paint); return
        }
        val left=stripes.left*pxPerMm; val top=stripes.top*pxPerMm; val wPx=(stripes.right-stripes.left)*pxPerMm; val hPx=(stripes.bottom-stripes.top)*pxPerMm
        val module=wPx/encoded.bits.length
        for(i in encoded.bits.indices) if(encoded.bits[i]=='1') canvas.drawRect(left+i*module, top, left+(i+1)*module, top+hPx, paint)
        val digits=encoded.display.takeLast(6)
        if(digits.isNotEmpty()){
            paint.textSize=geo.fontMm(data.barcodeLabelSize)*st.textScale*pxPerMm; paint.color=ink; paint.textAlign=Paint.Align.CENTER
            // 關鍵修正：數字的上緣 = 條紋底 + 固定 gap，下緣絕不覆蓋條紋
            // 用 cap-height 置中：基線 = digitsBaseline + ascent偏移
            val fm=paint.fontMetrics
            val baseline = digitsBaseline * pxPerMm - fm.ascent
            canvas.drawText(digits, left + wPx/2f, baseline, paint)
        }
    }

    private fun drawPrice(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, ink: Int, pxPerMm: Float, base: RectF){
        val data=geo.data; paint.style=Paint.Style.FILL; paint.color=ink; paint.typeface=Typeface.DEFAULT_BOLD
        val boxHPx=(base.bottom-base.top)*pxPerMm; val boxWPx=base.width()*pxPerMm; val leftPx=base.left*pxPerMm; val rightPx=base.right*pxPerMm
        fun money(v:String)=if(v.isBlank())"—" else v.trim()
        fun fitSize(req: Float, maxH: Float, sample: String, maxW: Float): Float {
            var s=req; paint.textSize=s; var needH=paint.fontMetrics.descent-paint.fontMetrics.ascent
            if(needH>maxH && needH>0f) s*=maxH/needH; paint.textSize=s; val w=paint.measureText(sample); if(w>maxW && w>0f) s*=maxW/w; return s
        }
        if(data.showMember){
            val (upper,lower)=geo.priceHalves(); val upperTop=upper.top*pxPerMm; val upperH=upper.height()*pxPerMm; val lowerTop=lower.top*pxPerMm; val lowerH=lower.height()*pxPerMm
            paint.style=Paint.Style.FILL; paint.color=Color.BLACK; canvas.drawRect(upper.left*pxPerMm, upper.top*pxPerMm, upper.right*pxPerMm, upper.bottom*pxPerMm, paint)
            val mMoney=money(data.member); val nMoney=money(data.nonMember)
            val mSize=fitSize(geo.fontMm(data.priceSize)*st.textScale*pxPerMm*0.82f, upperH*0.72f, "$ $mMoney", boxWPx*0.96f)
            drawMemberRow(canvas,"Member",mMoney,upperTop,upperH,mSize,leftPx,rightPx,Color.WHITE)
            val nSize=fitSize(geo.fontMm(data.priceSize)*st.textScale*pxPerMm*0.82f, lowerH*0.72f, "$ $nMoney", boxWPx*0.96f)
            drawMemberRow(canvas,"Price",nMoney,lowerTop,lowerH,nSize,leftPx,rightPx,ink)
        } else {
            val priceText=money(data.price); val sample="$ $priceText"
            val big=fitSize(geo.fontMm(data.priceSize)*st.textScale*pxPerMm, boxHPx*0.88f, sample, boxWPx*0.98f)
            val dollarSize=big*0.42f; paint.textSize=big; val fm=paint.fontMetrics; val cy=(base.top+base.bottom)/2f*pxPerMm; val baseline=cy-(fm.ascent+fm.descent)/2f
            paint.textSize=dollarSize; val dW=paint.measureText("$ "); paint.textSize=big; val nW=paint.measureText(priceText); val tot=dW+nW; val sx=rightPx-tot
            paint.textSize=dollarSize; paint.textAlign=Paint.Align.LEFT; canvas.drawText("$", sx, baseline, paint)
            paint.textSize=big; canvas.drawText(priceText, sx+dW, baseline, paint)
        }
    }
    private fun drawMemberRow(canvas: Canvas, label: String, value: String, topPx: Float, rowHPx: Float, big: Float, leftPx: Float, rightPx: Float, ink: Int){
        paint.color=ink; paint.style=Paint.Style.FILL; val small=big*0.34f
        val fmBig=run{ paint.textSize=big; paint.fontMetrics }; val fmSmall=run{ paint.textSize=small; paint.fontMetrics }
        val rowCy=topPx+rowHPx/2f; val bigBase=rowCy-(fmBig.ascent+fmBig.descent)/2f; val smallBase=rowCy-(fmSmall.ascent+fmSmall.descent)/2f
        val dSize=big*0.42f; paint.textSize=big; val nW=paint.measureText(value); paint.textSize=dSize; val dW=paint.measureText("$ "); val tot=dW+nW; val sx=rightPx-tot
        paint.textSize=small; paint.textAlign=Paint.Align.LEFT; canvas.drawText(label, leftPx+big*0.04f, smallBase, paint)
        paint.textSize=dSize; paint.textAlign=Paint.Align.LEFT; canvas.drawText("$", sx, bigBase, paint)
        paint.textSize=big; canvas.drawText(value, sx+dW, bigBase, paint)
    }
    private fun drawSelection(canvas: Canvas, geo: LabelGeometry, st: ElementStyle, element: LabelElement, pxPerMm: Float, ox: Float, oy: Float){
        val base=geo.base(element); val cx=(base.left+base.right)/2f; val cy=(base.top+base.bottom)/2f
        canvas.save(); canvas.translate(ox+st.dxMm*pxPerMm, oy+st.dyMm*pxPerMm); canvas.rotate(st.rotation.toFloat(), cx*pxPerMm, cy*pxPerMm); canvas.scale(st.scale, st.scale, cx*pxPerMm, cy*pxPerMm)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=(0.32f*pxPerMm).coerceIn(1f,4f); paint.color=Color.parseColor("#07C160")
        canvas.drawRoundRect(base.left*pxPerMm, base.top*pxPerMm, base.right*pxPerMm, base.bottom*pxPerMm, 4f,4f, paint)
        paint.style=Paint.Style.FILL; val r=paint.strokeWidth*1.7f
        for(p in arrayOf(base.left to base.top, base.right to base.top, base.right to base.bottom, base.left to base.bottom)) canvas.drawCircle(p.first*pxPerMm, p.second*pxPerMm, r, paint)
        canvas.restore()
    }
    private fun fit(text: String, maxWidth: Float): String {
        if(text.isEmpty()||maxWidth<=0f) return text; if(paint.measureText(text)<=maxWidth) return text
        var lo=0; var hi=text.length; while(lo<hi){ val mid=(lo+hi+1)/2; if(paint.measureText(text.substring(0,mid)+"…")<=maxWidth) lo=mid else hi=mid-1 }
        return if(lo<=0)"…" else text.substring(0,lo)+"…"
    }
}
