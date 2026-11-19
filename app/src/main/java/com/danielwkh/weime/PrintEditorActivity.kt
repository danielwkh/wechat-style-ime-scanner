package com.danielwkh.weime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import com.dothantech.printer.IDzPrinter.PrintProgress
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.dothantech.printer.IDzPrinter.PrinterState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 全新版面 · 標籤編輯器
 * 設計目標：截圖 2 款模板 × 2 尺寸 + 更現代、更流暢的操作
 * 畫布為中央卡片紙張（圓角 + 陰影），下方為分組卡片式設定區
 */
class PrintEditorActivity : Activity() {
    // ---- color palette
    private val C_BG = Color.parseColor("#F2F2F7")
    private val C_CANVAS_BG = Color.parseColor("#E9E9EB")
    private val C_CARD = Color.WHITE
    private val C_BORDER = Color.parseColor("#E5E5EA")
    private val C_BORDER_LIGHT = Color.parseColor("#F0F0F2")
    private val C_GREEN = Color.parseColor("#07C160")
    private val C_GREEN_DARK = Color.parseColor("#06AD56")
    private val C_TEXT = Color.parseColor("#1C1C1E")
    private val C_TEXT2 = Color.parseColor("#8E8E93")
    private val C_TEXT3 = Color.parseColor("#AEAEB2")
    private val C_FIELD_BG = Color.parseColor("#F9F9FB")

    private lateinit var preview: LabelPreviewView
    private lateinit var nameInput: EditText
    private lateinit var barcodeInput: EditText
    private lateinit var priceInput: EditText
    private lateinit var memberPriceInput: EditText
    private lateinit var nonMemberPriceInput: EditText
    private lateinit var memberMode: CheckBox
    private lateinit var sizeLabel: TextView
    private lateinit var printerButton: Button
    private var undoButton: View? = null
    private var redoButton: View? = null
    private lateinit var elementTitle: TextView
    private lateinit var elementTextScaleLabel: TextView
    private lateinit var boldButton: TextView
    private lateinit var positionLabel: TextView
    private lateinit var templateSummary: TextView

    private var device = LabelDevice.P1
    private var nameSize = 28
    private var priceSize = 58
    private var barcodeLabelSize = 17
    private var barcodeHeight = 58
    private var barcodeScale = 90
    private var barcodeFull = true
    private var barcodeType = BarcodeRenderer.TYPE_AUTO
    private var fullBarcodeValue = ""
    private lateinit var printerManager: DtPrinterManager
    private val discoveredPrinters = LinkedHashMap<String, PrinterAddress>()
    private var printCopies = 1
    private var printDirection = 0
    private var printGapLength = 3
    private var printDensity = -1
    private var printSpeed = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingShow: Runnable? = null

    private val styles = LabelElement.values().associateWith { ElementStyle() }.toMutableMap()
    private var selected = LabelElement.NAME
    private val undoStack = ArrayDeque<Map<LabelElement, ElementStyle>>()
    private val redoStack = ArrayDeque<Map<LabelElement, ElementStyle>>()
    private lateinit var layoutPrefs: android.content.SharedPreferences
    private lateinit var draftPrefs: android.content.SharedPreferences
    private var page = 0 // 0內容 1條碼 2樣式 3列印
    private lateinit var pagerBar: LinearLayout
    private lateinit var pageContent: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private val pageViews = HashMap<Int, View>()

    // element chips container
    private lateinit var elementChipsRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "列印標籤"
        printerManager = DtPrinterManager(this, object : DtPrinterManager.Listener {
            override fun onPrinterDiscovered(address: PrinterAddress) {
                runOnUiThread {
                    val key = address.macAddress ?: address.shownName ?: return@runOnUiThread
                    discoveredPrinters[key] = address
                }
            }
            override fun onPrinterState(address: PrinterAddress?, state: PrinterState) {
                runOnUiThread {
                    val btnOk = ::printerButton.isInitialized
                    when (state) {
                        PrinterState.Connected, PrinterState.Connected2 -> {
                            if (btnOk) printerButton.text = "已連線：${printerManager.printerName()}"
                            toast("印表機連線成功")
                        }
                        PrinterState.Disconnected -> {
                            if (btnOk) printerButton.text = "選擇印表機"
                        }
                        else -> Unit
                    }
                }
            }
            override fun onPrintProgress(progress: PrintProgress) {
                runOnUiThread {
                    if (progress == PrintProgress.Success) toast("列印完成")
                    if (progress == PrintProgress.Failed) toast("列印失敗")
                }
            }
        })
        draftPrefs = getSharedPreferences("kb", MODE_PRIVATE)
        layoutPrefs = getSharedPreferences("printLayout", MODE_PRIVATE)
        loadDraftAndLayout()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        root.addView(buildHeader())
        // canvas card-wrap
        val canvasWrap = FrameLayout(this).apply {
            setBackgroundColor(C_CANVAS_BG)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        preview = LabelPreviewView(this)
        // preview itself draws paper with shadow; wrap just provides bg
        canvasWrap.addView(preview, FrameLayout.LayoutParams(-1, -1))
        root.addView(canvasWrap, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(buildElementChips())
        root.addView(buildQuickToolbar())
        root.addView(buildPagerTabs())
        root.addView(buildBottomSheet())

        setContentView(root)
        installPreviewBinding()
        select(LabelElement.NAME)
        refreshUndoButtons()
        refreshElementChips()
    }

    private fun loadDraftAndLayout() {
        val draftBarcode = draftPrefs.getString("printBarcode", "").orEmpty()
        fullBarcodeValue = draftPrefs.getString("printFullBarcode", draftBarcode).orEmpty().ifBlank { draftBarcode }
        device = LabelDevice.of(layoutPrefs.getString("device", LabelDevice.P1.name))
        nameSize = layoutPrefs.getInt("nameSize", nameSize)
        priceSize = layoutPrefs.getInt("priceSize", priceSize)
        barcodeLabelSize = layoutPrefs.getInt("barcodeLabelSize", barcodeLabelSize)
        barcodeHeight = layoutPrefs.getInt("barcodeHeight", barcodeHeight)
        barcodeScale = layoutPrefs.getInt("barcodeScale", barcodeScale)
        barcodeFull = layoutPrefs.getBoolean("barcodeFull", true)
        barcodeType = layoutPrefs.getString("barcodeType", BarcodeRenderer.TYPE_AUTO).orEmpty()
        if (barcodeType !in BarcodeRenderer.types.map { it.first }) barcodeType = BarcodeRenderer.TYPE_AUTO
        printCopies = layoutPrefs.getInt("copies", 1)
        printDirection = layoutPrefs.getInt("direction", 0)
        printGapLength = layoutPrefs.getInt("gap", 3)
        printDensity = layoutPrefs.getInt("density", -1)
        printSpeed = layoutPrefs.getInt("speed", -1)
        for (e in LabelElement.values()) {
            val p = layoutPrefs
            styles[e] = ElementStyle(
                dxMm = p.getFloat("${e.name}_dx", 0f),
                dyMm = p.getFloat("${e.name}_dy", 0f),
                scale = p.getFloat("${e.name}_scale", 1f),
                rotation = p.getInt("${e.name}_rot", 0),
                invert = p.getBoolean("${e.name}_inv", false),
                textScale = p.getFloat("${e.name}_text", 1f),
                bold = p.getBoolean("${e.name}_bold", false),
            )
        }
    }

    private fun saveAll() {
        layoutPrefs.edit()
            .putString("device", device.name)
            .putInt("nameSize", nameSize).putInt("priceSize", priceSize)
            .putInt("barcodeLabelSize", barcodeLabelSize).putInt("barcodeHeight", barcodeHeight)
            .putInt("barcodeScale", barcodeScale).putBoolean("barcodeFull", barcodeFull)
            .putString("barcodeType", barcodeType)
            .putInt("copies", printCopies).putInt("direction", printDirection)
            .putInt("gap", printGapLength).putInt("density", printDensity).putInt("speed", printSpeed)
            .apply()
        val e = layoutPrefs.edit()
        for ((k, s) in styles) {
            e.putFloat("${k.name}_dx", s.dxMm); e.putFloat("${k.name}_dy", s.dyMm)
            e.putFloat("${k.name}_scale", s.scale); e.putInt("${k.name}_rot", s.rotation)
            e.putBoolean("${k.name}_inv", s.invert); e.putFloat("${k.name}_text", s.textScale)
            e.putBoolean("${k.name}_bold", s.bold)
        }
        e.apply()
    }

    // ---- UI builders

    private fun buildHeader(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C_CARD)
            setPadding(dp(8), dp(7), dp(12), dp(7))
            elevation = dp(1).toFloat()
        }
        // back
        val back = TextView(this).apply {
            text = "‹ 返回"; textSize = 15f; setTextColor(C_TEXT)
            setPadding(dp(6), dp(8), dp(10), dp(8))
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(-2, -2))
        // center title block
        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        center.addView(TextView(this).apply {
            text = "標籤編輯"; textSize = 16f; setTextColor(C_TEXT); gravity = Gravity.CENTER
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(-2, -2))
        center.addView(TextView(this).apply {
            text = "${device.widthMm.toInt()}×${device.heightMm.toInt()} mm · ${device.dpi} dpi"
            textSize = 11f; setTextColor(C_TEXT2); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2))
        bar.addView(center, LinearLayout.LayoutParams(0, -2, 1f))
        // print button - pill green
        val printBtn = TextView(this).apply {
            text = "▸ 列印"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pillBg(C_GREEN, dp(22).toFloat())
            setOnClickListener { onPrintClicked() }
        }
        bar.addView(printBtn, LinearLayout.LayoutParams(-2, dp(36)))
        // divider line below header is via elevation; also add thin line
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C_CARD) }
        wrap.addView(bar)
        wrap.addView(View(this).apply { setBackgroundColor(C_BORDER_LIGHT) }, LinearLayout.LayoutParams(-1, dp(1)))
        return wrap
    }

    // element chips: 商品名 / 條碼 / 價格
    private fun buildElementChips(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(C_CARD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        elementChipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scroll.addView(elementChipsRow)
        return scroll
    }

    private fun refreshElementChips() {
        if (!::elementChipsRow.isInitialized) return
        elementChipsRow.removeAllViews()
        for (e in LabelElement.values()) {
            val chip = TextView(this).apply {
                text = e.title; textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                val isSel = e == selected
                setTextColor(if (isSel) Color.WHITE else C_TEXT2)
                background = pillBg(if (isSel) C_GREEN else Color.parseColor("#F0F0F2"), dp(20).toFloat())
                setOnClickListener { select(e); refreshElementChips() }
            }
            elementChipsRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
    }

    // quick toolbar: undo/redo | align | rotate/reset
    private fun buildQuickToolbar(): View {
        val wrap = LinearLayout(this).apply {
            setBackgroundColor(C_CARD)
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(C_FIELD_BG, dp(10).toFloat(), C_BORDER, dp(1))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun iconBtn(label: String, onClick: () -> Unit): View {
            return TextView(this).apply {
                text = label; textSize = 13f; setTextColor(C_TEXT); gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundedBg(Color.WHITE, dp(8).toFloat(), C_BORDER, dp(1))
                setOnClickListener { onClick() }
            }
        }
        // undo/redo group
        val undoV = iconBtn("↶") { undo() }.also { undoButton = it }
        val redoV = iconBtn("↷") { redo() }.also { redoButton = it }
        card.addView(undoV, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2),0,dp(2),0) })
        card.addView(redoV, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2),0,dp(8),0) })
        card.addView(dividerV(), LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(0,0,dp(8),0) })
        // align group
        val aligns = listOf("←" to "left", "↔" to "hcenter", "→" to "right", "↑" to "top", "⊡" to "vcenter", "↓" to "bottom")
        for ((lbl, mode) in aligns) {
            card.addView(iconBtn(lbl) { align(mode) }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(1),0,dp(1),0) })
        }
        card.addView(dividerV(), LinearLayout.LayoutParams(dp(1), dp(22)).apply { setMargins(dp(8),0,dp(8),0) })
        card.addView(iconBtn("↻ 旋轉") {
            pushUndo(); val s = styles.getValue(selected); s.rotation = (s.rotation + 90) % 360
            refreshElementBar(); preview.invalidate(); saveAll()
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2),0,dp(2),0) })
        card.addView(iconBtn("◐ 反白") {
            pushUndo(); val s = styles.getValue(selected); s.invert = !s.invert; preview.invalidate(); saveAll()
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2),0,dp(2),0) })
        card.addView(iconBtn("↺ 重設") {
            pushUndo(); styles.getValue(selected).reset(); refreshElementBar(); preview.invalidate(); saveAll()
        }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(2),0,dp(2),0) })
        // scroll container for toolbar if overflow
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(card) }
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun dividerV(): View = View(this).apply { setBackgroundColor(C_BORDER) }

    // segmented pager tabs
    private fun buildPagerTabs(): View {
        val wrap = LinearLayout(this).apply {
            setBackgroundColor(C_CARD)
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        pagerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(Color.parseColor("#EFEFF0"), dp(10).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val labels = listOf("內容", "條碼", "樣式", "列印")
        for ((i, label) in labels.withIndex()) {
            pagerBar.addView(TextView(this).apply {
                text = label; textSize = 13f; gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { page = i; refreshPager() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        wrap.addView(pagerBar, LinearLayout.LayoutParams(-1, -2))
        return wrap
    }

    private fun buildBottomSheet(): View {
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(C_CARD)
            addView(pageContent)
        }
        settingsScroll = scroll
        // top rounded corners for sheet
        scroll.background = GradientDrawable().apply {
            setColor(C_CARD)
            cornerRadii = floatArrayOf(dp(16).toFloat(),dp(16).toFloat(),dp(16).toFloat(),dp(16).toFloat(),0f,0f,0f,0f)
            setStroke(dp(1), C_BORDER_LIGHT)
        }
        // subtle elevation
        scroll.elevation = dp(4).toFloat()
        return scroll.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(320)) }
    }

    private val pageViews2 = HashMap<Int, View>()
    private fun refreshPager() {
        for (i in 0 until pagerBar.childCount) {
            val tv = pagerBar.getChildAt(i) as? TextView ?: continue
            val isSel = i == page
            tv.setTextColor(if (isSel) C_TEXT else C_TEXT2)
            tv.background = if (isSel) roundedBg(Color.WHITE, dp(8).toFloat(), C_BORDER, dp(1)) else null
            tv.paint.isFakeBoldText = isSel
            tv.elevation = if (isSel) dp(1).toFloat() else 0f
        }
        pageContentView()
    }
    private fun pageContentView() {
        val cached = pageViews2[page]
        pageContent.removeAllViews()
        if (cached != null) { (cached.parent as? LinearLayout)?.removeView(cached); pageContent.addView(cached); return }
        val v = when (page) { 0 -> buildContentPage(); 1 -> buildBarcodePage(); 2 -> buildSizePage(); else -> buildPrintPage() }
        pageViews2[page] = v; pageContent.addView(v)
    }

    // ---- pages with card-style groups

    private fun sectionCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(C_CARD, dp(12).toFloat(), C_BORDER, dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun cardTitle(text: String): View = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(C_TEXT); paint.isFakeBoldText = true
        setPadding(0,0,0,dp(8))
    }

    private fun hintText(text: String): View = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(C_TEXT2); setPadding(0,0,0,dp(6)); setLineSpacing(0f, 1.2f)
    }

    private fun modernField(hint: String, initial: String = ""): EditText {
        return EditText(this).apply {
            this.hint = hint; textSize = 15f; setTextColor(C_TEXT); setHintTextColor(C_TEXT3)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBg(C_FIELD_BG, dp(9).toFloat(), C_BORDER, dp(1))
            setSingleLine(true)
            if (initial.isNotEmpty()) setText(initial)
            onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                background = roundedBg(if(hasFocus) Color.WHITE else C_FIELD_BG, dp(9).toFloat(), if(hasFocus) C_GREEN else C_BORDER, if(hasFocus) dp(1) else dp(1))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a:Int,b:Int,c:Int)=Unit
                override fun onTextChanged(s: CharSequence?, a:Int,b:Int,c:Int){ if (::preview.isInitialized) preview.invalidate() }
                override fun afterTextChanged(s: Editable?)=Unit
            })
        }
    }

    // 縮略圖：70×40 比例的迷你標籤示意
    private fun templateThumb(isMember: Boolean): View {
        val thumb = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(6).toFloat(), C_BORDER, dp(1))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val nameBar = View(this).apply { setBackgroundColor(Color.parseColor("#E8E8EB")) }
        thumb.addView(nameBar, LinearLayout.LayoutParams(-1, dp(8)).apply{ bottomMargin=dp(4)})
        val bottomRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val barcodeBox = LinearLayout(this).apply { setBackgroundColor(Color.parseColor("#1C1C1E")); setPadding(dp(2), dp(2), dp(2), dp(2)) }
        for (k in 0 until 4) {
            val stripe = View(this).apply { setBackgroundColor(Color.WHITE) }
            barcodeBox.addView(stripe, LinearLayout.LayoutParams(dp(2), dp(12)).apply { setMargins(dp(1), 0, dp(1), 0) })
        }
        bottomRow.addView(barcodeBox, LinearLayout.LayoutParams(0, dp(18), 0.42f).apply{ setMargins(0,0,dp(4),0)})
        if (isMember) {
            val priceCol = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
            priceCol.addView(View(this).apply{ setBackgroundColor(Color.parseColor("#1C1C1E")) }, LinearLayout.LayoutParams(-1, dp(9)).apply{ bottomMargin=dp(2)})
            priceCol.addView(View(this).apply{ setBackgroundColor(Color.parseColor("#F0F0F0")); background=roundedBg(Color.WHITE, dp(2).toFloat(), C_BORDER, dp(1)) }, LinearLayout.LayoutParams(-1, dp(9)))
            bottomRow.addView(priceCol, LinearLayout.LayoutParams(0, -2, 0.58f))
        } else {
            bottomRow.addView(View(this).apply{ setBackgroundColor(Color.parseColor("#07C160")); background=roundedBg(Color.parseColor("#07C160"), dp(3).toFloat()) }, LinearLayout.LayoutParams(0, dp(18), 0.58f))
        }
        thumb.addView(bottomRow, LinearLayout.LayoutParams(-1, -2))
        return thumb
    }

    private fun buildContentPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(hintText("掃描條碼或選擇商品後，品名、條碼與價格會自動帶入預覽，並依是否有會員價自動切換。"))
        // template cards with thumbnails
        val card1 = sectionCard()
        card1.addView(cardTitle("模板樣式"))
        val rowT = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val templateCards = mutableListOf<View>()
        fun refreshTemplateCards(){
            val isMem = try{ memberMode.isChecked } catch(_:Exception){ false }
            for((idx, card) in templateCards.withIndex()){
                val shouldSel = if(idx==0) !isMem else isMem
                card.background = roundedBg(if(shouldSel) Color.parseColor("#E8F5E9") else C_FIELD_BG, dp(10).toFloat(), if(shouldSel) C_GREEN else C_BORDER, if(shouldSel) dp(2) else dp(1))
                (card as LinearLayout).getChildAt(0).let{ (it as TextView).text = if(shouldSel) "✓" else "○"; it.setTextColor(if(shouldSel) C_GREEN else C_TEXT3) }
                (card.getChildAt(1) as TextView).paint.isFakeBoldText = shouldSel
            }
            updateTemplateSummary()
        }
        fun makeTemplateCard(title: String, sub: String, isForMember: Boolean): View {
            val isSelInit = try{ if(isForMember) memberMode.isChecked else !memberMode.isChecked } catch(_:Exception){ !isForMember }
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = roundedBg(if(isSelInit) Color.parseColor("#E8F5E9") else C_FIELD_BG, dp(10).toFloat(), if(isSelInit) C_GREEN else C_BORDER, if(isSelInit) dp(2) else dp(1))
                setPadding(dp(10), dp(12), dp(10), dp(12))
                isClickable = true; isFocusable = true
            }
            c.addView(TextView(this).apply { text=if(isSelInit)"✓" else "○"; textSize=14f; setTextColor(if(isSelInit) C_GREEN else C_TEXT3); gravity=Gravity.CENTER })
            c.addView(templateThumb(isForMember), LinearLayout.LayoutParams(dp(72), dp(36)).apply{ topMargin=dp(6); bottomMargin=dp(6)})
            c.addView(TextView(this).apply { text=title; textSize=13f; setTextColor(C_TEXT); gravity=Gravity.CENTER; paint.isFakeBoldText=isSelInit })
            c.addView(TextView(this).apply { text=sub; textSize=10f; setTextColor(C_TEXT2); gravity=Gravity.CENTER })
            c.setOnClickListener{
                if(!::memberMode.isInitialized) return@setOnClickListener
                memberMode.isChecked = isForMember
            }
            return c
        }
        val t1 = makeTemplateCard("無會員價","右側單一大價", false)
        val t2 = makeTemplateCard("會員價版","Member / Price", true)
        templateCards.add(t1); templateCards.add(t2)
        rowT.addView(t1, LinearLayout.LayoutParams(0, -2, 1f).apply{ setMargins(0,0,dp(8),0)})
        rowT.addView(t2, LinearLayout.LayoutParams(0, -2, 1f))
        card1.addView(rowT)
        templateSummary = TextView(this).apply { textSize=11f; setTextColor(C_GREEN); setPadding(0,dp(8),0,0) }
        card1.addView(templateSummary)
        col.addView(card1, LinearLayout.LayoutParams(-1,-2).apply{ bottomMargin=dp(12)})

        // size chips - dynamic without rebuild
        val cardSize = sectionCard()
        cardSize.addView(cardTitle("標籤尺寸"))
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val sizeChips = mutableListOf<TextView>()
        fun refreshSizeChips(){
            for((idx, chip) in sizeChips.withIndex()){
                val d = LabelDevice.values()[idx]
                val isSel = d == device
                chip.text = (if(isSel)"● " else "○ ") + when(d){ LabelDevice.P1->"中標籤 70×40 mm  · 203 dpi"; LabelDevice.P1P->"中標籤 70×40 mm  · 300 dpi（更清晰）"; LabelDevice.DP40->"大標籤 180×100 mm · 203 dpi"}
                chip.setTextColor(if(isSel) C_TEXT else C_TEXT2)
                chip.background = roundedBg(if(isSel) Color.parseColor("#E8F5E9") else C_FIELD_BG, dp(8).toFloat(), if(isSel) C_GREEN else C_BORDER_LIGHT, dp(1))
            }
            updateTemplateSummary(); updateSizeLabel()
        }
        for (d in LabelDevice.values()) {
            val chip = TextView(this).apply {
                textSize=13f; setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener{
                    device = d; preview.invalidate(); saveAll()
                    val geo = LabelGeometry(device, currentData()); for((k,st) in styles) geo.clamp(st, geo.base(k)); preview.invalidate()
                    refreshSizeChips()
                }
            }
            sizeChips.add(chip)
            sizeRow.addView(chip, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(6)})
        }
        refreshSizeChips()
        cardSize.addView(sizeRow)
        col.addView(cardSize, LinearLayout.LayoutParams(-1,-2).apply{ bottomMargin=dp(12)})

        // product content
        val cardProd = sectionCard()
        cardProd.addView(cardTitle("商品資訊（掃描後自動帶入）"))
        nameInput = modernField("商品名稱")
        priceInput = modernField("價格（無會員價時顯示）")
        memberPriceInput = modernField("會員價 Member $")
        nonMemberPriceInput = modernField("非會員價 Price $")
        memberMode = CheckBox(this).apply {
            text = "顯示會員價版（上黑下白）"; textSize=13f; setTextColor(C_TEXT)
            setOnCheckedChangeListener { _, checked ->
                memberPriceInput.visibility = if(checked) View.VISIBLE else View.GONE
                nonMemberPriceInput.visibility = if(checked) View.VISIBLE else View.GONE
                refreshTemplateCards(); preview.invalidate()
            }
        }
        cardProd.addView(nameInput, LinearLayout.LayoutParams(-1, dp(44)).apply{ topMargin=dp(6)})
        cardProd.addView(priceInput, LinearLayout.LayoutParams(-1, dp(44)).apply{ topMargin=dp(8)})
        cardProd.addView(memberMode, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(10)})
        cardProd.addView(memberPriceInput, LinearLayout.LayoutParams(-1, dp(44)).apply{ topMargin=dp(8)})
        cardProd.addView(nonMemberPriceInput, LinearLayout.LayoutParams(-1, dp(44)).apply{ topMargin=dp(8)})
        col.addView(cardProd)
        try {
            nameInput.setText(draftPrefs.getString("printName","").orEmpty())
            val dpPrice = draftPrefs.getString("printPrice","").orEmpty()
            val dpNon = draftPrefs.getString("printNonMemberPrice","").orEmpty()
            val dpMem = draftPrefs.getString("printMemberPrice","").orEmpty()
            val eff = dpPrice.ifBlank{ dpNon }
            priceInput.setText(eff); memberPriceInput.setText(dpMem); nonMemberPriceInput.setText(dpNon.ifBlank{ eff })
            val hasMember = dpMem.isNotBlank()
            memberMode.isChecked = hasMember
            memberPriceInput.visibility = if(hasMember) View.VISIBLE else View.GONE
            nonMemberPriceInput.visibility = if(hasMember) View.VISIBLE else View.GONE
        } catch(_: Exception){}
        col.post { refreshTemplateCards(); refreshSizeChips() }
        return col
    }

    private fun updateTemplateSummary() {
        if (!::templateSummary.isInitialized) return
        val sizeName = if (device==LabelDevice.DP40) "大標籤 180×100" else "中標籤 70×40"
        val isMem = try{ memberMode.isChecked } catch(_: Exception){ false }
        templateSummary.text = if(isMem) "目前：$sizeName · 會員價版（右側上黑 Member / 下白 Price）" else "目前：$sizeName · 無會員價（右側單一大價）"
    }

    private fun buildBarcodePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(hintText("條碼會自動依類型編碼，數字固定顯示後 6 位於條紋下方（不會覆蓋條紋）。"))
        val card = sectionCard()
        card.addView(cardTitle("條碼內容"))
        barcodeInput = modernField("條碼數字")
        barcodeInput.inputType = InputType.TYPE_CLASS_NUMBER
        try { barcodeInput.setText(if(barcodeFull) fullBarcodeValue else fullBarcodeValue.takeLast(6)) } catch(_: Exception){}
        barcodeInput.addTextChangedListener(object: TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int)=Unit
            override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){ if(barcodeFull) fullBarcodeValue=s?.toString().orEmpty() }
            override fun afterTextChanged(s:Editable?)=Unit
        })
        card.addView(barcodeInput, LinearLayout.LayoutParams(-1, dp(44)))
        // full/6 toggle as segmented
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(Color.parseColor("#EFEFF0"), dp(8).toFloat())
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        fun segBtn(lbl:String, isSel:Boolean, onClick:()->Unit): View = TextView(this).apply{
            text=lbl; textSize=12f; gravity=Gravity.CENTER; setPadding(dp(12),dp(8),dp(12),dp(8))
            setTextColor(if(isSel) C_TEXT else C_TEXT2)
            background = if(isSel) roundedBg(Color.WHITE, dp(6).toFloat(), C_BORDER, dp(1)) else null
            setOnClickListener{ onClick() }
        }
        val b1 = segBtn("列印全碼", barcodeFull){ barcodeFull=true; val v=fullBarcodeValue; barcodeInput.setText(v); barcodeInput.setSelection(v.length); saveAll(); preview.invalidate(); pageViews2.remove(1) }
        val b2 = segBtn("只印後六碼", !barcodeFull){ barcodeFull=false; val v=fullBarcodeValue.takeLast(6); barcodeInput.setText(v); barcodeInput.setSelection(v.length); saveAll(); preview.invalidate(); pageViews2.remove(1) }
        seg.addView(b1, LinearLayout.LayoutParams(0,-2,1f).apply{ setMargins(dp(2),0,dp(2),0)})
        seg.addView(b2, LinearLayout.LayoutParams(0,-2,1f).apply{ setMargins(dp(2),0,dp(2),0)})
        card.addView(seg, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(10)})
        card.addView(TextView(this).apply{
            text="顯示於標籤上的數字為後 6 位，條紋仍為完整編碼"; textSize=11f; setTextColor(C_TEXT3); setPadding(0,dp(6),0,0)
        })
        col.addView(card, LinearLayout.LayoutParams(-1,-2).apply{ bottomMargin=dp(12)})

        val card2 = sectionCard()
        card2.addView(cardTitle("條碼類型"))
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@PrintEditorActivity, android.R.layout.simple_spinner_dropdown_item, BarcodeRenderer.types.map{ it.second })
            setSelection(BarcodeRenderer.types.indexOfFirst{ it.first==barcodeType }.coerceAtLeast(0))
            onItemSelectedListener = object: AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(p: AdapterView<*>?)=Unit
                override fun onItemSelected(p: AdapterView<*>?,v:View?,pos:Int,id:Long){ barcodeType=BarcodeRenderer.types[pos].first; saveAll(); preview.invalidate() }
            }
        }
        // wrap spinner in card field bg
        val spinWrap = LinearLayout(this).apply {
            background = roundedBg(C_FIELD_BG, dp(8).toFloat(), C_BORDER, dp(1)); setPadding(dp(4),dp(4),dp(4),dp(4))
            addView(typeSpinner, LinearLayout.LayoutParams(-1, dp(40)))
        }
        card2.addView(spinWrap)
        card2.addView(TextView(this).apply{ text="自動：8碼→EAN-8、12/13碼→EAN-13、其他→Code128"; textSize=11f; setTextColor(C_TEXT3); setPadding(0,dp(6),0,0)})
        col.addView(card2)
        return col
    }

    private fun buildSizePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(hintText("拖曳紙張上的元素可移動，雙指可縮放。每個元素可獨立調整。"))
        // selected element detail card
        val cardSel = sectionCard()
        // title row with position
        val titleRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        elementTitle = TextView(this).apply { textSize=13f; setTextColor(C_TEXT); paint.isFakeBoldText=true; text="選取：${selected.title}" }
        positionLabel = TextView(this).apply { textSize=11f; setTextColor(C_TEXT2); gravity=Gravity.END; text="x 0.0  y 0.0  0°" }
        titleRow.addView(elementTitle, LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(positionLabel, LinearLayout.LayoutParams(dp(120),-2))
        cardSel.addView(titleRow)
        // slider row
        val row2 = LinearLayout(this).apply{ orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(8),0,0) }
        elementTextScaleLabel = TextView(this).apply{ textSize=12f; setTextColor(C_TEXT2); text="字級 100%"; gravity=Gravity.CENTER }
        row2.addView(elementTextScaleLabel, LinearLayout.LayoutParams(dp(70),-2))
        val seek = SeekBar(this).apply {
            max=160; progress=60
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b: SeekBar?, p:Int, fromUser:Boolean){
                    val v=40+p; elementTextScaleLabel.text="字級 ${v}%"
                    if(fromUser){ pushUndo(); styles.getValue(selected).textScale=v/100f; preview.invalidate(); saveAll() }
                }
                override fun onStartTrackingTouch(b:SeekBar?)=pushUndo()
                override fun onStopTrackingTouch(b:SeekBar?)=Unit
            })
        }
        row2.addView(seek, LinearLayout.LayoutParams(0, dp(32), 1f).apply{ setMargins(dp(8),0,dp(8),0)})
        boldButton = TextView(this).apply{
            text="B"; textSize=14f; gravity=Gravity.CENTER; setPadding(dp(10),dp(6),dp(10),dp(6))
            background=roundedBg(Color.WHITE, dp(8).toFloat(), C_BORDER, dp(1)); setTextColor(C_TEXT)
            setOnClickListener{ pushUndo(); val s=styles.getValue(selected); s.bold=!s.bold; refreshElementBar(); preview.invalidate(); saveAll() }
        }
        row2.addView(boldButton, LinearLayout.LayoutParams(dp(40), dp(32)))
        cardSel.addView(row2)
        col.addView(cardSel, LinearLayout.LayoutParams(-1,-2).apply{ bottomMargin=dp(12)})

        val cardCtl = sectionCard()
        cardCtl.addView(cardTitle("尺寸與縮放"))
        cardCtl.addView(sizeControl("商品名", 12,40, nameSize){ nameSize=it })
        cardCtl.addView(sizeControl("價格", 20,86, priceSize){ priceSize=it })
        cardCtl.addView(sizeControl("條碼數字", 10,30, barcodeLabelSize){ barcodeLabelSize=it })
        cardCtl.addView(sizeControl("條紋高度", 25,90, barcodeHeight){ barcodeHeight=it })
        cardCtl.addView(sizeControl("條紋寬度", 40,100, barcodeScale){ barcodeScale=it })
        col.addView(cardCtl)
        // initial refresh
        col.post{ refreshElementBar() }
        return col
    }

    private fun buildPrintPage(): View {
        val col = LinearLayout(this).apply{ orientation=LinearLayout.VERTICAL }
        val cardP = sectionCard()
        cardP.addView(cardTitle("印表機"))
        val btn = Button(this).apply{
            text="選擇印表機"; textSize=14f; setTextColor(Color.WHITE)
            background=pillBg(C_GREEN, dp(10).toFloat())
            setOnClickListener{ discoverPrinters() }
        }
        printerButton = btn
        cardP.addView(btn, LinearLayout.LayoutParams(-1, dp(44)))
        sizeLabel = TextView(this).apply{ textSize=11f; setTextColor(C_TEXT2); setPadding(0,dp(8),0,0)}
        cardP.addView(sizeLabel)
        col.addView(cardP, LinearLayout.LayoutParams(-1,-2).apply{ bottomMargin=dp(12)})
        updateSizeLabel()

        val cardParam = sectionCard()
        cardParam.addView(cardTitle("列印參數"))
        cardParam.addView(numberField("列印份數", printCopies){ printCopies=it.coerceIn(1,999)})
        cardParam.addView(numberField("間隔長度（mm）", printGapLength){ printGapLength=it.coerceIn(0,50)})
        val dir = Spinner(this).apply{
            adapter=ArrayAdapter(this@PrintEditorActivity, android.R.layout.simple_spinner_dropdown_item, listOf("出紙方向 0°","出紙方向 90°","出紙方向 180°","出紙方向 270°"))
            setSelection((printDirection/90).coerceIn(0,3))
            onItemSelectedListener=object: AdapterView.OnItemSelectedListener{
                override fun onNothingSelected(p: AdapterView<*>?)=Unit
                override fun onItemSelected(p: AdapterView<*>?,v:View?,pos:Int,id:Long){ printDirection=pos*90; saveAll() }
            }
        }
        val dirWrap = LinearLayout(this).apply{ background=roundedBg(C_FIELD_BG, dp(8).toFloat(), C_BORDER, dp(1)); setPadding(dp(4),dp(4),dp(4),dp(4)); addView(dir, LinearLayout.LayoutParams(-1, dp(40))) }
        cardParam.addView(dirWrap, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(8)})
        cardParam.addView(sizeControl("列印濃度（0=預設）",0,20,if(printDensity<0)0 else printDensity){ printDensity=if(it==0)-1 else it })
        cardParam.addView(sizeControl("列印速度（0=預設）",0,5,if(printSpeed<0)0 else printSpeed){ printSpeed=if(it==0)-1 else it })
        col.addView(cardParam)
        return col
    }

    private fun updateSizeLabel(){ if(!::sizeLabel.isInitialized) return; sizeLabel.text="標籤 ${device.widthMm.toInt()}×${device.heightMm.toInt()} mm　${device.bitmapWidth}×${device.bitmapHeight} px · ${device.dpi} dpi" }

    // helpers
    private fun roundedBg(color:Int, radius:Float, stroke:Int?=null, sw:Int=0): GradientDrawable {
        return GradientDrawable().apply{
            setColor(color); cornerRadius=radius
            if(stroke!=null) setStroke(sw, stroke)
        }
    }
    private fun pillBg(color:Int, radius:Float): GradientDrawable = GradientDrawable().apply{ setColor(color); cornerRadius=radius }

    private fun numberField(hint:String, value:Int, onChange:(Int)->Unit): View {
        val input = modernField(hint, value.toString()).apply{
            inputType = InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener{ _, hasFocus -> if(!hasFocus){ val p=text.toString().toIntOrNull()?:value; onChange(p); setText(p.toString()); saveAll() } }
        }
        return input.apply{ layoutParams = LinearLayout.LayoutParams(-1, dp(44)).apply{ topMargin=dp(8)} }
    }

    private fun sizeControl(label:String, minV:Int, maxV:Int, initial:Int, onChange:(Int)->Unit): View {
        val row = LinearLayout(this).apply{ orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(6),0,dp(6))}
        val v = TextView(this).apply{ text="$label $initial"; textSize=12f; setTextColor(C_TEXT2) }
        row.addView(v, LinearLayout.LayoutParams(dp(110), -2))
        val seek = SeekBar(this).apply{
            max=maxV-minV; progress=(initial-minV).coerceIn(0,maxV-minV)
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(b:SeekBar?,p:Int,fromUser:Boolean){ val cur=minV+p; v.text="$label $cur"; onChange(cur); if(::preview.isInitialized) preview.invalidate() }
                override fun onStartTrackingTouch(b:SeekBar?)=Unit
                override fun onStopTrackingTouch(b:SeekBar?){ saveAll()}
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, dp(28), 1f))
        return row
    }

    private fun dp(v:Int) = (v*resources.displayMetrics.density).toInt()

    // ---- preview binding
    private fun installPreviewBinding(){
        preview.read={ currentData() }
        preview.styles=styles
        preview.onSelect={ e-> selected=e; refreshElementBar(); refreshElementChips() }
        preview.canvasInfo={ device }
        preview.onUndoSnapshot={ pushUndo() }
        preview.onMoved={ refreshElementBar(); saveAll(); refreshUndoButtons() }
        refreshPager()
    }

    private fun currentData(): LabelData{
        if(!::nameInput.isInitialized || !::priceInput.isInitialized || !::memberMode.isInitialized || !::barcodeInput.isInitialized){
            return LabelData().apply{
                name=draftPrefs.getString("printName","").orEmpty()
                barcode=fullBarcodeValue.ifBlank{ draftPrefs.getString("printBarcode","").orEmpty()}
                price=draftPrefs.getString("printPrice","").orEmpty().ifBlank{ draftPrefs.getString("printNonMemberPrice","").orEmpty()}
                member=draftPrefs.getString("printMemberPrice","").orEmpty()
                nonMember=draftPrefs.getString("printNonMemberPrice","").orEmpty().ifBlank{ price}
                showMember=member.isNotBlank()
                nameSize=this@PrintEditorActivity.nameSize; priceSize=this@PrintEditorActivity.priceSize
                barcodeLabelSize=this@PrintEditorActivity.barcodeLabelSize
                barcodeHeight=this@PrintEditorActivity.barcodeHeight
                barcodeScale=this@PrintEditorActivity.barcodeScale
                barcodeType=this@PrintEditorActivity.barcodeType
            }
        }
        return LabelData().apply{
            name=nameInput.text.toString(); barcode=barcodeInput.text.toString()
            price=priceInput.text.toString(); member=memberPriceInput.text.toString()
            nonMember=nonMemberPriceInput.text.toString(); showMember=memberMode.isChecked
            nameSize=this@PrintEditorActivity.nameSize; priceSize=this@PrintEditorActivity.priceSize
            barcodeLabelSize=this@PrintEditorActivity.barcodeLabelSize
            barcodeHeight=this@PrintEditorActivity.barcodeHeight
            barcodeScale=this@PrintEditorActivity.barcodeScale
            barcodeType=this@PrintEditorActivity.barcodeType
        }
    }

    private fun select(e:LabelElement){ selected=e; refreshElementBar(); refreshElementChips(); preview.selected=e; preview.invalidate() }
    private fun refreshElementBar(){
        if(!::elementTitle.isInitialized) return
        val st=styles.getValue(selected)
        elementTitle.text="選取：${selected.title}"
        elementTextScaleLabel.text="${(st.textScale*100).toInt()}%"
        boldButton.background = roundedBg(if(st.bold) C_GREEN else Color.WHITE, dp(8).toFloat(), if(st.bold) C_GREEN else C_BORDER, dp(1))
        boldButton.setTextColor(if(st.bold) Color.WHITE else C_TEXT)
        positionLabel.text="x %.1f  y %.1f  %d°".format(st.dxMm, st.dyMm, st.rotation)
    }

    private fun snapshot():Map<LabelElement, ElementStyle> = styles.mapValues{ it.value.copy()}
    private fun pushUndo(){ undoStack.addLast(snapshot()); if(undoStack.size>60) undoStack.removeFirst(); redoStack.clear(); refreshUndoButtons()}
    private fun restore(snap:Map<LabelElement, ElementStyle>){ for((k,v) in snap) styles[k]=v.copy(); preview.invalidate(); refreshElementBar(); saveAll()}
    private fun undo(){ if(undoStack.isEmpty()) return; redoStack.addLast(snapshot()); restore(undoStack.removeLast()); refreshUndoButtons()}
    private fun redo(){ if(redoStack.isEmpty()) return; undoStack.addLast(snapshot()); restore(redoStack.removeLast()); refreshUndoButtons()}
    private fun refreshUndoButtons(){ undoButton?.alpha=if(undoStack.isEmpty())0.4f else 1f; redoButton?.alpha=if(redoStack.isEmpty())0.4f else 1f }
    private fun align(mode:String){
        pushUndo(); val st=styles.getValue(selected); val geo=LabelGeometry(device, currentData()); val base=geo.base(selected)
        val pts=geo.corners(base,st); var minX=Float.MAX_VALUE; var maxX=-Float.MAX_VALUE; var minY=Float.MAX_VALUE; var maxY=-Float.MAX_VALUE
        for(p in pts){ minX=min(minX,p.x); maxX=max(maxX,p.x); minY=min(minY,p.y); maxY=max(maxY,p.y)}
        when(mode){ "left"->st.dxMm+=-minX; "right"->st.dxMm+=geo.w-maxX; "hcenter"->st.dxMm+=(geo.w-(minX+maxX))/2f; "top"->st.dyMm+=-minY; "bottom"->st.dyMm+=geo.h-maxY; "vcenter"->st.dyMm+=(geo.h-(minY+maxY))/2f }
        refreshElementBar(); preview.invalidate(); saveAll()
    }

    private fun discoverPrinters(){
        val adapter=BluetoothAdapter.getDefaultAdapter()
        if(adapter==null){ toast("此裝置不支援藍牙"); return }
        val perms=if(android.os.Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing=perms.filter{ checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isNotEmpty()){ requestPermissions(missing.toTypedArray(),410); return }
        if(!adapter.isEnabled){ toast("請先開啟藍牙"); return }
        discoveredPrinters.clear()
        if(::printerButton.isInitialized) printerButton.text="搜尋印表機中…"
        try{ printerManager.discover()}catch(e:Exception){ toast("啟動搜尋失敗：${e.message}"); return }
        pendingShow?.let{ mainHandler.removeCallbacks(it)}
        pendingShow=Runnable{ if(!isFinishing && !isDestroyed) showPrinterChoices() }
        mainHandler.postDelayed(pendingShow!!,1800)
    }
    private fun showPrinterChoices(){
        pendingShow?.let{ mainHandler.removeCallbacks(it)}; pendingShow=null
        try{ printerManager.stopDiscovery()}catch(_:Exception){}
        if(isFinishing||isDestroyed) return
        val entries=discoveredPrinters.values.toList()
        if(entries.isEmpty()){ if(::printerButton.isInitialized) printerButton.text="選擇印表機"; toast("找不到印表機，請確認印表機已開機並靠近手機"); return }
        val labels=entries.map{ it.shownName?.takeIf(String::isNotBlank)?:it.macAddress.orEmpty()}.toTypedArray()
        try{
            AlertDialog.Builder(this).setTitle("選擇印表機").setItems(labels){_,which->
                if(::printerButton.isInitialized) printerButton.text="連線中…"
                try{ if(!printerManager.connect(entries[which])){ if(::printerButton.isInitialized) printerButton.text="選擇印表機"; toast("無法提交連線要求")}}catch(e:Exception){ if(::printerButton.isInitialized) printerButton.text="選擇印表機"; toast("連線失敗：${e.message}") }
            }.setNegativeButton("取消",null).show()
        }catch(e:Exception){ toast("顯示列表失敗：${e.message}") }
    }
    private fun onPrintClicked(){
        if(!::printerManager.isInitialized || !printerManager.isConnected()){ toast("請先選擇並連線印表機"); page=3; refreshPager(); discoverPrinters(); return }
        val bitmap=try{ preview.renderBitmap()}catch(e:Exception){ toast("產生列印圖片失敗：${e.message}"); return }
        val submitted=try{ printerManager.print(bitmap, printCopies, printDirection, printGapLength, printDensity, printSpeed)}catch(e:Exception){ toast("列印失敗：${e.message}"); false }
        if(!submitted) toast("列印要求未能送出") else toast("已送出 ${bitmap.width}×${bitmap.height} px 標籤")
    }
    override fun onRequestPermissionsResult(rc:Int, perms:Array<out String>, res:IntArray){
        super.onRequestPermissionsResult(rc,perms,res)
        if(rc==410 && res.isNotEmpty() && res.all{ it==PackageManager.PERMISSION_GRANTED}) discoverPrinters()
    }
    override fun onDestroy(){ pendingShow?.let{ mainHandler.removeCallbacks(it)}; pendingShow=null; if(::printerManager.isInitialized) try{ printerManager.close()}catch(_:Exception){}; super.onDestroy()}
    private fun toast(m:String)=Toast.makeText(this,m,Toast.LENGTH_SHORT).show()

    private inner class LabelPreviewView(ctx:Context): View(ctx){
        var read:(()->LabelData)?=null
        var styles:MutableMap<LabelElement,ElementStyle> = mutableMapOf()
        var selected:LabelElement=LabelElement.NAME
        var canvasInfo:(()->LabelDevice)?=null
        var onSelect:((LabelElement)->Unit)?=null
        var onUndoSnapshot:(()->Unit)?=null
        var onMoved:(()->Unit)?=null
        private val painter=LabelPainter()
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX=0f; private var downY=0f; private var lastX=0f; private var lastY=0f
        private var baseDistance=0f; private var baseScale=1f; private var dragging=false; private var movedOnce=false
        private var pxPerMm=1f; private var ox=0f; private var oy=0f
        private fun computeLayout(){
            val dev=canvasInfo?.invoke()?:LabelDevice.P1
            val pad=dp(14).toFloat()
            val availW=(width-pad*2).coerceAtLeast(1f); val availH=(height-pad*2).coerceAtLeast(1f)
            pxPerMm=min(availW/dev.widthMm, availH/dev.heightMm)
            val w=dev.widthMm*pxPerMm; val h=dev.heightMm*pxPerMm
            ox=(width-w)/2f; oy=(height-h)/2f
        }
        override fun onDraw(canvas: Canvas){
            super.onDraw(canvas)
            val data=try{ read?.invoke()}catch(_:Exception){null} ?: return
            val dev=canvasInfo?.invoke()?:LabelDevice.P1
            try{
                computeLayout()
                val geo=LabelGeometry(dev,data)
                canvas.drawColor(C_CANVAS_BG)
                // paper shadow + rounded paper
                paint.style=Paint.Style.FILL; paint.color=Color.WHITE
                // shadow
                paint.setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), Color.parseColor("#1A000000"))
                canvas.drawRoundRect(ox, oy, ox+dev.widthMm*pxPerMm, oy+dev.heightMm*pxPerMm, dp(8).toFloat(), dp(8).toFloat(), paint)
                paint.clearShadowLayer()
                // clip to paper rounded
                canvas.save()
                canvas.clipRoundRect(ox, oy, ox+dev.widthMm*pxPerMm, oy+dev.heightMm*pxPerMm, dp(8).toFloat())
                canvas.drawColor(Color.WHITE)
                try{
                    painter.draw(canvas, geo, styles, pxPerMm, ox, oy, selected, showSelection=true)
                }catch(_:Exception){
                    paint.style=Paint.Style.FILL; paint.color=Color.rgb(190,45,45); paint.textAlign=Paint.Align.CENTER; paint.textSize=dp(12).toFloat()
                    canvas.drawText("預覽繪製失敗", ox+dev.widthMm*pxPerMm/2f, oy+dev.heightMm*pxPerMm/2f, paint)
                }
                canvas.restore()
                // paper border
                paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1).toFloat(); paint.color=Color.parseColor("#E0E0E5")
                canvas.drawRoundRect(ox, oy, ox+dev.widthMm*pxPerMm, oy+dev.heightMm*pxPerMm, dp(8).toFloat(), dp(8).toFloat(), paint)
                drawRuler(canvas, dev)
            }catch(_:Exception){}
        }
        private fun Canvas.clipRoundRect(l:Float,t:Float,r:Float,b:Float, rad:Float){
            val path=android.graphics.Path().apply{ addRoundRect(android.graphics.RectF(l,t,r,b), rad, rad, android.graphics.Path.Direction.CW) }
            clipPath(path)
        }
        private fun drawRuler(canvas: Canvas, dev:LabelDevice){
            paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(1).toFloat(); paint.color=Color.parseColor("#C7C7CC"); paint.textSize=dp(7).toFloat()
            paint.textAlign=Paint.Align.LEFT
            var mm=0; while(mm<=dev.widthMm.toInt()){
                val x=ox+mm*pxPerMm; val len=if(mm%50==0) dp(6) else dp(3)
                canvas.drawLine(x, oy-len, x, oy, paint)
                if(mm%20==0){ paint.style=Paint.Style.FILL; canvas.drawText(mm.toString(), x+dp(1), oy-dp(6), paint); paint.style=Paint.Style.STROKE }
                mm+=10
            }
            paint.textAlign=Paint.Align.RIGHT; mm=0
            while(mm<=dev.heightMm.toInt()){
                val y=oy+mm*pxPerMm; val len=if(mm%50==0) dp(6) else dp(3)
                canvas.drawLine(ox-len, y, ox, y, paint)
                if(mm%20==0){ paint.style=Paint.Style.FILL; canvas.drawText(mm.toString(), ox-dp(2), y+dp(3), paint); paint.style=Paint.Style.STROKE }
                mm+=10
            }
        }
        fun renderBitmap(): android.graphics.Bitmap{
            val data=read?.invoke()?:LabelData(); val dev=canvasInfo?.invoke()?:LabelDevice.P1; val geo=LabelGeometry(dev,data)
            val out=android.graphics.Bitmap.createBitmap(dev.bitmapWidth, dev.bitmapHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val c=Canvas(out); c.drawColor(Color.WHITE)
            painter.draw(c, geo, styles, dev.pxPerMm, 0f,0f, null as LabelElement?, false); return out
        }
        override fun onTouchEvent(e: MotionEvent): Boolean{
            val dev=canvasInfo?.invoke()?:return true; val data=try{ read?.invoke()}catch(_:Exception){null}?:return true
            if (width > 0 && height > 0) computeLayout()
            val geo=LabelGeometry(dev,data); val toMm={ v:Float,o:Float-> (v-o)/pxPerMm }
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{ downX=e.x; downY=e.y; lastX=e.x; lastY=e.y; dragging=false; movedOnce=false; baseDistance=0f
                    val hit=geo.hitTest(styles,toMm(e.x,ox),toMm(e.y,oy)); if(hit!=null && hit!=selected) onSelect?.invoke(hit) }
                MotionEvent.ACTION_POINTER_DOWN-> if(e.pointerCount>=2){ baseDistance=hypot(e.getX(0)-e.getX(1), e.getY(0)-e.getY(1)); baseScale=styles.getValue(selected).scale; onUndoSnapshot?.invoke(); movedOnce=true }
                MotionEvent.ACTION_MOVE->{
                    val st=styles.getValue(selected)
                    if(e.pointerCount>=2 && baseDistance>0f){ st.scale=(baseScale*hypot(e.getX(0)-e.getX(1), e.getY(0)-e.getY(1))/baseDistance).coerceIn(0.3f,3.5f); geo.clamp(st,geo.base(selected)); movedOnce=true; invalidate() }
                    else{
                        val dx=(e.x-lastX)/pxPerMm; val dy=(e.y-lastY)/pxPerMm
                        if(!dragging && hypot(e.x-downX,e.y-downY)>dp(4)){ dragging=true; onUndoSnapshot?.invoke() }
                        if(dragging && (abs(dx)>0f||abs(dy)>0f)){ st.dxMm+=dx; st.dyMm+=dy; geo.clamp(st,geo.base(selected)); lastX=e.x; lastY=e.y; movedOnce=true; invalidate() }
                        lastX=e.x; lastY=e.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL->{ baseDistance=0f; if(movedOnce) onMoved?.invoke(); dragging=false }
            }
            return true
        }
        private fun hypot(a:Float,b:Float)=kotlin.math.hypot(a,b)
        private fun distance(e:MotionEvent)=hypot(e.getX(0)-e.getX(1), e.getY(0)-e.getY(1))
    }
}
