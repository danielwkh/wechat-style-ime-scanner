package com.danielwkh.weime

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dothantech.printer.IDzPrinter.PrintProgress
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.dothantech.printer.IDzPrinter.PrinterState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 標籤編輯器（版面參考 WePrint）：
 * 上方所見即所得的畫布 → 選取元素屬性列 → 對齊／旋轉／撤銷工具列 → 分頁式設定。
 *
 * 三項與舊版不同的關鍵修正：
 * 1. 列印點陣改依機型 mm × dpi 產生（原本直接用螢幕預覽 View 的像素送去印，解析度嚴重不足）。
 * 2. 機型選擇真正驅動畫布比例（原本 device 變數選了沒有任何作用）。
 * 3. 繪圖、點選、列印共用同一份 LabelGeometry，點選不再因兩邊各自重算而錯位。
 */
class PrintEditorActivity : Activity() {
    private lateinit var preview: LabelPreviewView
    private lateinit var nameInput: EditText
    private lateinit var barcodeInput: EditText
    private lateinit var priceInput: EditText
    private lateinit var memberPriceInput: EditText
    private lateinit var nonMemberPriceInput: EditText
    private lateinit var memberMode: CheckBox
    private lateinit var sizeLabel: TextView
    private lateinit var printerButton: Button
    private var undoButton: Button? = null
    private var redoButton: Button? = null
    private lateinit var elementTitle: TextView
    private lateinit var elementTextScaleLabel: TextView
    private lateinit var boldButton: Button
    private lateinit var positionLabel: TextView

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

    /** 撤銷／重做堆疊；每次操作前先記錄一份快照。 */
    private val undoStack = ArrayDeque<Map<LabelElement, ElementStyle>>()
    private val redoStack = ArrayDeque<Map<LabelElement, ElementStyle>>()

    private lateinit var layoutPrefs: android.content.SharedPreferences
    private lateinit var draftPrefs: android.content.SharedPreferences
    private var page = 0

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
                    // 注意：LPAPI 可能在 printerButton 尚未初始化（尚未切到「列印」分頁）時就回調
                    // 直接存取會拋 UninitializedPropertyAccessException 導致 Activity 1 秒後閃退
                    val btnOk = ::printerButton.isInitialized
                    when (state) {
                        PrinterState.Connected, PrinterState.Connected2 -> {
                            if (btnOk) printerButton.text = "已連線：${printerManager.printerName()}"
                            toast("印表機連線成功")
                        }
                        PrinterState.Disconnected -> {
                            if (btnOk) printerButton.text = "選擇印表機"
                            // 避免在首次開啟就因初始狀態為 Disconnected 而誤顯示斷線提示
                            // 只在曾經連線過的情況才 toast（以按鈕曾顯示過「已連線」為依據）
                            // 這裡簡化：不主動 toast 斷線，由使用者操作時再提示
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
            setBackgroundColor(Color.rgb(245, 245, 247))
        }
        root.addView(buildHeader())

        preview = LabelPreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(buildElementBar())
        root.addView(buildToolBar())
        root.addView(buildPager())
        root.addView(buildScrollableSettings())

        setContentView(root)
        installPreviewBinding()
        select(LabelElement.NAME)
        refreshUndoButtons()
    }

    // ---------------------------------------------------------------- 載入草稿

    private fun loadDraftAndLayout() {
        val draftBarcode = draftPrefs.getString("printBarcode", "").orEmpty()
        fullBarcodeValue = draftPrefs.getString("printFullBarcode", draftBarcode).orEmpty()
            .ifBlank { draftBarcode }
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
            .putInt("nameSize", nameSize)
            .putInt("priceSize", priceSize)
            .putInt("barcodeLabelSize", barcodeLabelSize)
            .putInt("barcodeHeight", barcodeHeight)
            .putInt("barcodeScale", barcodeScale)
            .putBoolean("barcodeFull", barcodeFull)
            .putString("barcodeType", barcodeType)
            .putInt("copies", printCopies)
            .putInt("direction", printDirection)
            .putInt("gap", printGapLength)
            .putInt("density", printDensity)
            .putInt("speed", printSpeed)
            .apply()
        val e = layoutPrefs.edit()
        for ((k, s) in styles) {
            e.putFloat("${k.name}_dx", s.dxMm)
            e.putFloat("${k.name}_dy", s.dyMm)
            e.putFloat("${k.name}_scale", s.scale)
            e.putInt("${k.name}_rot", s.rotation)
            e.putBoolean("${k.name}_inv", s.invert)
            e.putFloat("${k.name}_text", s.textScale)
            e.putBoolean("${k.name}_bold", s.bold)
        }
        e.apply()
    }

    // ---------------------------------------------------------------- 版面元件

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        header.addView(Button(this).apply {
            text = "‹ 返回"
            textSize = 13f
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(84), dp(42)))
        header.addView(TextView(this).apply {
            text = "標籤編輯"
            textSize = 17f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(Button(this).apply {
            text = "列印"
            textSize = 14f
            setBackgroundColor(Color.rgb(7, 193, 96))
            setTextColor(Color.WHITE)
            setOnClickListener { onPrintClicked() }
        }, LinearLayout.LayoutParams(dp(76), dp(42)))
        return header
    }

    /** 選取元素列：顯示目前選到什麼，並可就地調整該元素的字級與粗體。 */
    private fun buildElementBar(): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        elementTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        row.addView(elementTitle, LinearLayout.LayoutParams(0, dp(30), 1f))
        positionLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        row.addView(positionLabel, LinearLayout.LayoutParams(dp(110), dp(30)))
        wrap.addView(row)
        val row2 = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        elementTextScaleLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            text = "字級 100%"
        }
        row2.addView(elementTextScaleLabel, LinearLayout.LayoutParams(dp(96), dp(38)))
        val seek = SeekBar(this).apply {
            max = 160
            progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = 40 + progress
                    elementTextScaleLabel.text = "字級 $v%"
                    if (fromUser) {
                        pushUndo()
                        styles.getValue(selected).textScale = v / 100f
                        preview.invalidate()
                        saveAll()
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = pushUndo()
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        row2.addView(seek, LinearLayout.LayoutParams(0, dp(38), 1f))
        boldButton = Button(this).apply {
            text = "B"
            textSize = 15f
            setOnClickListener {
                pushUndo()
                val s = styles.getValue(selected)
                s.bold = !s.bold
                refreshElementBar()
                preview.invalidate()
                saveAll()
            }
        }
        row2.addView(boldButton, LinearLayout.LayoutParams(dp(44), dp(38)))
        wrap.addView(row2)
        return wrap
    }

    private fun buildToolBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.WHITE)
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        fun tool(label: String, click: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            setPadding(0, 0, 0, 0)
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(40)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            }
        }
        val undo = tool("↶ 撤銷") { undo() }.also { undoButton = it }
        val redo = tool("↷ 重做") { redo() }.also { redoButton = it }
        row.addView(undo)
        row.addView(redo)
        row.addView(tool("左") { align("left") })
        row.addView(tool("中") { align("hcenter") })
        row.addView(tool("右") { align("right") })
        row.addView(tool("上") { align("top") })
        row.addView(tool("中") { align("vcenter") })
        row.addView(tool("下") { align("bottom") })
        row.addView(tool("旋轉") {
            pushUndo()
            val s = styles.getValue(selected)
            s.rotation = (s.rotation + 90) % 360
            refreshElementBar()
            preview.invalidate()
            saveAll()
        })
        row.addView(tool("反白") {
            pushUndo()
            val s = styles.getValue(selected)
            s.invert = !s.invert
            preview.invalidate()
            saveAll()
        })
        row.addView(tool("重設") {
            pushUndo()
            styles.getValue(selected).reset()
            refreshElementBar()
            preview.invalidate()
            saveAll()
        })
        scroll.addView(row)
        return scroll
    }

    /** 分頁切換；設定區一次只顯示一頁，避免舊版一路長捲動。 */
    private fun buildPager(): View {
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        val labels = listOf("內容", "條碼", "尺寸", "列印")
        for ((i, label) in labels.withIndex()) {
            bar.addView(Button(this).apply {
                text = label
                textSize = 13f
                setOnClickListener {
                    page = i
                    refreshPager()
                }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        pagerBar = bar
        return bar
    }

    private lateinit var pagerBar: LinearLayout
    private lateinit var pageContent: LinearLayout

    private fun buildScrollableSettings(): View {
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        val scroll = ScrollView(this).apply {
            addView(pageContent)
        }
        settingsScroll = scroll
        return scroll.apply { layoutParams = LinearLayout.LayoutParams(-1, dp(232)) }
    }

    private lateinit var settingsScroll: ScrollView
    private val pageViews = HashMap<Int, View>()

    private fun refreshPager() {
        for (i in 0 until pagerBar.childCount) {
            val b = pagerBar.getChildAt(i) as? Button ?: continue
            b.setBackgroundColor(if (i == page) Color.rgb(7, 193, 96) else Color.rgb(232, 232, 235))
            b.setTextColor(if (i == page) Color.WHITE else Color.DKGRAY)
        }
        pageContentView()
    }

    private fun pageContentView() {
        val cached = pageViews[page]
        pageContent.removeAllViews()
        if (cached != null) {
            (cached.parent as? LinearLayout)?.removeView(cached)
            pageContent.addView(cached)
            return
        }
        val view = when (page) {
            0 -> buildContentPage()
            1 -> buildBarcodePage()
            2 -> buildSizePage()
            else -> buildPrintPage()
        }
        pageViews[page] = view
        pageContent.addView(view)
    }

    // ---------------------------------------------------------------- 各分頁

    private fun buildContentPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 模板說明：對齊截圖 2 款（無會員價／會員價版）× 2 尺寸（中 70×40／大 180×100）
        col.addView(TextView(this).apply {
            text = "掃描或選擇商品後，品名、條碼、價格與會員價會自動帶入，並依是否有會員價自動選擇模板（無會員價＝右側單一大價；會員價版＝右側上下兩排 Member/Price）。"
            textSize = 12f; setTextColor(Color.GRAY); setPadding(0, 0, 0, dp(6))
        })
        col.addView(sectionTitle("標籤尺寸"))
        val deviceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (d in LabelDevice.values()) {
            val label = when (d) {
                LabelDevice.P1 -> "中標籤 70 × 40 mm（P1／203 dpi）"
                LabelDevice.P1P -> "中標籤 70 × 40 mm（P1P／300 dpi，更清晰）"
                LabelDevice.DP40 -> "大標籤 180 × 100 mm（DP40／203 dpi）"
            }
            deviceGroup.addView(RadioButton(this).apply {
                text = label
                tag = d
                isChecked = d == device
            })
        }
        deviceGroup.setOnCheckedChangeListener { group, checkedId ->
            val picked = group.findViewById<RadioButton>(checkedId).tag as LabelDevice
            if (picked != device) {
                device = picked
                updateSizeLabel()
                preview.invalidate()
                saveAll()
                val geo = LabelGeometry(device, currentData())
                for ((k, st) in styles) geo.clamp(st, geo.base(k))
                preview.invalidate()
            }
        }
        col.addView(deviceGroup)
        col.addView(sectionTitle("模板"))
        val templateSummary = TextView(this).apply {
            textSize = 12f; setTextColor(Color.rgb(7, 120, 70)); setPadding(0, dp(2), 0, dp(4))
        }
        col.addView(templateSummary)
        fun refreshTemplateSummary() {
            val sizeName = if (device == LabelDevice.DP40) "大標籤 180×100" else "中標籤 70×40"
            templateSummary.text = if (memberMode.isChecked) "目前：$sizeName · 會員價版（右側 Member $ / Price $，上黑下白）"
            else "目前：$sizeName · 無會員價（右側單一巨大價格）"
        }
        col.addView(sectionTitle("商品內容（掃描後自動帶入）"))
        nameInput = field("商品名稱")
        priceInput = field("價格（無會員價時顯示在此）")
        memberPriceInput = field("會員價（Member $）")
        nonMemberPriceInput = field("非會員價（Price $）")
        memberMode = CheckBox(this).apply {
            text = "會員價版（右側兩排 Member / Price，上黑下白）"
            setOnCheckedChangeListener { _, checked ->
                memberPriceInput.visibility = if (checked) View.VISIBLE else View.GONE
                nonMemberPriceInput.visibility = if (checked) View.VISIBLE else View.GONE
                refreshTemplateSummary()
                preview.invalidate()
            }
        }
        col.addView(nameInput)
        col.addView(priceInput)
        col.addView(memberMode)
        col.addView(memberPriceInput)
        col.addView(nonMemberPriceInput)

        // 從掃描保存的 prefs 自動帶入：printName / printPrice / printMemberPrice / printNonMemberPrice
        nameInput.setText(draftPrefs.getString("printName", "").orEmpty())
        val draftPrice = draftPrefs.getString("printPrice", "").orEmpty()
        val draftNonMember = draftPrefs.getString("printNonMemberPrice", "").orEmpty()
        val draftMember = draftPrefs.getString("printMemberPrice", "").orEmpty()
        // effective 為原價／非會員價（兩者其一）
        val effective = draftPrice.ifBlank { draftNonMember }
        priceInput.setText(effective)
        memberPriceInput.setText(draftMember)
        nonMemberPriceInput.setText(draftNonMember.ifBlank { effective })
        // 只依「會員價」是否有值決定是否切為會員價版；避免僅有非會員價就誤判為會員價版
        val hasMember = draftMember.isNotBlank()
        memberMode.isChecked = hasMember
        memberPriceInput.visibility = if (hasMember) View.VISIBLE else View.GONE
        nonMemberPriceInput.visibility = if (hasMember) View.VISIBLE else View.GONE
        refreshTemplateSummary()
        // 若預覽與保存的模板不一致（如剛掃描完有會員價），立即刷新一次
        if (hasMember) preview.invalidate()
        return col
    }

    private fun buildBarcodePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionTitle("條碼內容"))
        barcodeInput = field("條碼")
        barcodeInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        barcodeInput.setText(if (barcodeFull) fullBarcodeValue else fullBarcodeValue.takeLast(6))
        barcodeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (barcodeFull) fullBarcodeValue = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        col.addView(barcodeInput)

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@PrintEditorActivity).apply {
                id = View.generateViewId(); text = "列印全碼"; tag = true
            })
            addView(RadioButton(this@PrintEditorActivity).apply {
                id = View.generateViewId(); text = "只印後六碼"; tag = false
            })
            setOnCheckedChangeListener { group, checkedId ->
                barcodeFull = group.findViewById<RadioButton>(checkedId).tag as Boolean
                val value = if (barcodeFull) fullBarcodeValue else fullBarcodeValue.takeLast(6)
                barcodeInput.setText(value)
                barcodeInput.setSelection(value.length)
                saveAll()
                preview.invalidate()
            }
        }
        (modeGroup.getChildAt(if (barcodeFull) 0 else 1) as RadioButton).isChecked = true
        col.addView(modeGroup)

        col.addView(sectionTitle("條碼類型"))
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PrintEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                BarcodeRenderer.types.map { it.second },
            )
            setSelection(BarcodeRenderer.types.indexOfFirst { it.first == barcodeType }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    barcodeType = BarcodeRenderer.types[position].first
                    saveAll()
                    preview.invalidate()
                }
            }
        }
        col.addView(typeSpinner, LinearLayout.LayoutParams(-1, dp(46)))
        col.addView(TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            text = "自動判斷：8 碼 → EAN-8、12／13 碼 → EAN-13、其他 → Code 128"
            setPadding(0, dp(4), 0, 0)
        })
        return col
    }

    private fun buildSizePage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionTitle("字級與條碼尺寸"))
        col.addView(sizeControl("商品名字級", 12, 40, nameSize) { nameSize = it })
        col.addView(sizeControl("價格字級", 20, 86, priceSize) { priceSize = it })
        col.addView(sizeControl("條碼數字字級", 10, 30, barcodeLabelSize) { barcodeLabelSize = it })
        col.addView(sizeControl("條碼高度", 25, 90, barcodeHeight) { barcodeHeight = it })
        col.addView(sizeControl("條碼寬度", 40, 100, barcodeScale) { barcodeScale = it })
        return col
    }

    private fun buildPrintPage(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(sectionTitle("印表機"))
        printerButton = Button(this).apply {
            text = "選擇印表機"
            setOnClickListener { discoverPrinters() }
        }
        col.addView(printerButton)
        sizeLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, 0)
        }
        col.addView(sizeLabel)
        updateSizeLabel()

        col.addView(sectionTitle("列印參數"))
        col.addView(numberField("列印份數", printCopies) { printCopies = it.coerceIn(1, 999) })
        col.addView(numberField("間隔長度（mm）", printGapLength) { printGapLength = it.coerceIn(0, 50) })
        val direction = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@PrintEditorActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("出紙方向 0°", "出紙方向 90°", "出紙方向 180°", "出紙方向 270°"),
            )
            setSelection((printDirection / 90).coerceIn(0, 3))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    printDirection = position * 90
                    saveAll()
                }
            }
        }
        col.addView(direction, LinearLayout.LayoutParams(-1, dp(46)))
        col.addView(sizeControl("列印濃度（0 = 預設）", 0, 20, if (printDensity < 0) 0 else printDensity) {
            printDensity = if (it == 0) -1 else it
        })
        col.addView(sizeControl("列印速度（0 = 預設）", 0, 5, if (printSpeed < 0) 0 else printSpeed) {
            printSpeed = if (it == 0) -1 else it
        })
        return col
    }

    // ---------------------------------------------------------------- 小工具

    private fun updateSizeLabel() {
        if (!::sizeLabel.isInitialized) return
        sizeLabel.text = "標籤 ${device.widthMm.toInt()} × ${device.heightMm.toInt()} mm" +
            "　列印點陣 ${device.bitmapWidth} × ${device.bitmapHeight} px（${device.dpi} dpi）"
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(7, 120, 70))
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun field(hint: String): android.widget.EditText = android.widget.EditText(this).apply {
        setHint(hint)
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(10), 0, dp(10), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), Color.LTGRAY)
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(3), 0, dp(3)) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (::preview.isInitialized) preview.invalidate()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun numberField(hint: String, value: Int, onChange: (Int) -> Unit): View {
        val input = field(hint).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(value.toString())
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val parsed = text.toString().toIntOrNull() ?: value
                    onChange(parsed)
                    setText(parsed.toString())
                    saveAll()
                }
            }
        }
        return input
    }

    private fun sizeControl(label: String, minV: Int, maxV: Int, initial: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = TextView(this).apply {
            text = "$label $initial"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        row.addView(value, LinearLayout.LayoutParams(dp(120), dp(42)))
        val seek = SeekBar(this).apply {
            max = maxV - minV
            progress = (initial - minV).coerceIn(0, maxV - minV)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = minV + progress
                    value.text = "$label $current"
                    onChange(current)
                    if (::preview.isInitialized) preview.invalidate()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    saveAll()
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(0, dp(42), 1f))
        return row
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 預覽與互動

    private fun installPreviewBinding() {
        preview.read = { currentData() }
        preview.styles = styles
        preview.onSelect = { e ->
            selected = e
            refreshElementBar()
        }
        preview.canvasInfo = { device }
        preview.onUndoSnapshot = { pushUndo() }
        preview.onMoved = {
            refreshElementBar()
            saveAll()
            refreshUndoButtons()
        }
        refreshPager()
    }

    private fun currentData(): LabelData {
        // 首次 onDraw 可能在輸入框尚未初始化前呼叫（例如 preview 提前 layout），
        // 此時直接以 prefs 草稿為後備，避免 UninitializedPropertyAccessException 閃退。
        if (!::nameInput.isInitialized || !::priceInput.isInitialized ||
            !::memberMode.isInitialized || !::barcodeInput.isInitialized
        ) {
            return LabelData().apply {
                name = draftPrefs.getString("printName", "").orEmpty()
                barcode = fullBarcodeValue.ifBlank { draftPrefs.getString("printBarcode", "").orEmpty() }
                price = draftPrefs.getString("printPrice", "").orEmpty()
                    .ifBlank { draftPrefs.getString("printNonMemberPrice", "").orEmpty() }
                member = draftPrefs.getString("printMemberPrice", "").orEmpty()
                nonMember = draftPrefs.getString("printNonMemberPrice", "").orEmpty().ifBlank { price }
                showMember = member.isNotBlank()
                nameSize = this@PrintEditorActivity.nameSize
                priceSize = this@PrintEditorActivity.priceSize
                barcodeLabelSize = this@PrintEditorActivity.barcodeLabelSize
                barcodeHeight = this@PrintEditorActivity.barcodeHeight
                barcodeScale = this@PrintEditorActivity.barcodeScale
                barcodeType = this@PrintEditorActivity.barcodeType
            }
        }
        return LabelData().apply {
            name = nameInput.text.toString()
            barcode = barcodeInput.text.toString()
            price = priceInput.text.toString()
            member = memberPriceInput.text.toString()
            nonMember = nonMemberPriceInput.text.toString()
            showMember = memberMode.isChecked
            nameSize = this@PrintEditorActivity.nameSize
            priceSize = this@PrintEditorActivity.priceSize
            barcodeLabelSize = this@PrintEditorActivity.barcodeLabelSize
            barcodeHeight = this@PrintEditorActivity.barcodeHeight
            barcodeScale = this@PrintEditorActivity.barcodeScale
            barcodeType = this@PrintEditorActivity.barcodeType
        }
    }

    private fun select(e: LabelElement) {
        selected = e
        refreshElementBar()
        preview.selected = e
        preview.invalidate()
    }

    private fun refreshElementBar() {
        val st = styles.getValue(selected)
        elementTitle.text = "選取：${selected.title}"
        elementTextScaleLabel.text = "字級 ${(st.textScale * 100).toInt()}%"
        boldButton.setBackgroundColor(if (st.bold) Color.rgb(7, 193, 96) else Color.rgb(232, 232, 235))
        boldButton.setTextColor(if (st.bold) Color.WHITE else Color.DKGRAY)
        positionLabel.text = "x %.1f  y %.1f  %d°".format(st.dxMm, st.dyMm, st.rotation)
    }

    // ---------------------------------------------------------------- 撤銷／重做

    private fun snapshot(): Map<LabelElement, ElementStyle> =
        styles.mapValues { it.value.copy() }

    private fun pushUndo() {
        undoStack.addLast(snapshot())
        if (undoStack.size > 60) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoButtons()
    }

    private fun restore(snap: Map<LabelElement, ElementStyle>) {
        for ((k, v) in snap) styles[k] = v.copy()
        preview.invalidate()
        refreshElementBar()
        saveAll()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(snapshot())
        restore(undoStack.removeLast())
        refreshUndoButtons()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(snapshot())
        restore(redoStack.removeLast())
        refreshUndoButtons()
    }

    private fun refreshUndoButtons() {
        undoButton?.alpha = if (undoStack.isEmpty()) 0.4f else 1f
        redoButton?.alpha = if (redoStack.isEmpty()) 0.4f else 1f
    }

    /** 對齊目前選取元素；位移以 mm 為單位，預覽與列印結果一致。 */
    private fun align(mode: String) {
        pushUndo()
        val st = styles.getValue(selected)
        val geo = LabelGeometry(device, currentData())
        val base = geo.base(selected)
        val pts = geo.corners(base, st)
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (p in pts) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        when (mode) {
            "left" -> st.dxMm += -minX
            "right" -> st.dxMm += geo.w - maxX
            "hcenter" -> st.dxMm += (geo.w - (minX + maxX)) / 2f
            "top" -> st.dyMm += -minY
            "bottom" -> st.dyMm += geo.h - maxY
            "vcenter" -> st.dyMm += (geo.h - (minY + maxY)) / 2f
        }
        refreshElementBar()
        preview.invalidate()
        saveAll()
    }

    // ---------------------------------------------------------------- 印表機

    private fun discoverPrinters() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            toast("此裝置不支援藍牙")
            return
        }
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 410)
            return
        }
        if (!adapter.isEnabled) {
            toast("請先開啟藍牙")
            return
        }
        discoveredPrinters.clear()
        if (::printerButton.isInitialized) printerButton.text = "搜尋印表機中…"
        try {
            printerManager.discover()
        } catch (e: Exception) {
            toast("啟動搜尋失敗：${e.message}")
            return
        }
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = Runnable { if (!isFinishing && !isDestroyed) showPrinterChoices() }
        mainHandler.postDelayed(pendingShow!!, 1800)
    }

    private fun showPrinterChoices() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
        try { printerManager.stopDiscovery() } catch (_: Exception) {}
        if (isFinishing || isDestroyed) return
        val entries = discoveredPrinters.values.toList()
        if (entries.isEmpty()) {
            if (::printerButton.isInitialized) printerButton.text = "選擇印表機"
            toast("找不到印表機，請確認印表機已開機並靠近手機")
            return
        }
        val labels = entries.map {
            it.shownName?.takeIf(String::isNotBlank) ?: it.macAddress.orEmpty()
        }.toTypedArray()
        try {
            AlertDialog.Builder(this)
                .setTitle("選擇印表機")
                .setItems(labels) { _, which ->
                    if (::printerButton.isInitialized) printerButton.text = "連線中…"
                    try {
                        if (!printerManager.connect(entries[which])) {
                            if (::printerButton.isInitialized) printerButton.text = "選擇印表機"
                            toast("無法提交連線要求")
                        }
                    } catch (e: Exception) {
                        if (::printerButton.isInitialized) printerButton.text = "選擇印表機"
                        toast("連線失敗：${e.message}")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            toast("顯示列表失敗：${e.message}")
        }
    }

    private fun onPrintClicked() {
        if (!::printerManager.isInitialized || !printerManager.isConnected()) {
            toast("請先選擇並連線印表機")
            page = 3
            refreshPager()
            discoverPrinters()
            return
        }
        val bitmap = try {
            preview.renderBitmap()
        } catch (e: Exception) {
            toast("產生列印圖片失敗：${e.message}")
            return
        }
        val submitted = try {
            printerManager.print(
                bitmap = bitmap,
                copies = printCopies,
                direction = printDirection,
                gapLength = printGapLength,
                density = printDensity,
                speed = printSpeed,
            )
        } catch (e: Exception) {
            toast("列印失敗：${e.message}")
            false
        }
        if (!submitted) toast("列印要求未能送出") else toast("已送出 ${bitmap.width} × ${bitmap.height} px 標籤")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            discoverPrinters()
        }
    }

    override fun onDestroy() {
        pendingShow?.let { mainHandler.removeCallbacks(it) }
        pendingShow = null
        if (::printerManager.isInitialized) try { printerManager.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /**
     * 畫布：依機型比例繪製白色標籤，並顯示 mm 標尺。
     *
     * renderBitmap() 會依「機型 mm × dpi」重新配置 Canvas，而不是沿用螢幕上 View 的像素尺寸，
     * 因此列印點陣的條碼模組寬度與字級才是正確的。
     */
    private inner class LabelPreviewView(context: Context) : View(context) {
        var read: (() -> LabelData)? = null
        var styles: MutableMap<LabelElement, ElementStyle> = mutableMapOf()
        var selected: LabelElement = LabelElement.NAME
        var canvasInfo: (() -> LabelDevice)? = null
        var onSelect: ((LabelElement) -> Unit)? = null
        var onUndoSnapshot: (() -> Unit)? = null
        var onMoved: (() -> Unit)? = null

        private val painter = LabelPainter()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var baseDistance = 0f
        private var baseScale = 1f
        private var dragging = false
        private var movedOnce = false

        /** mm → 螢幕 px 的縮放與標籤左上角位置。 */
        private var pxPerMm = 1f
        private var ox = 0f
        private var oy = 0f

        private fun computeLayout() {
            val dev = canvasInfo?.invoke() ?: LabelDevice.P1
            val pad = dp(12).toFloat()
            val availW = (width - pad * 2).coerceAtLeast(1f)
            val availH = (height - pad * 2).coerceAtLeast(1f)
            pxPerMm = min(availW / dev.widthMm, availH / dev.heightMm)
            val w = dev.widthMm * pxPerMm
            val h = dev.heightMm * pxPerMm
            ox = (width - w) / 2f
            oy = (height - h) / 2f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val data = try { read?.invoke() } catch (_: Exception) { null } ?: return
            val dev = canvasInfo?.invoke() ?: LabelDevice.P1
            try {
                computeLayout()
                val geo = LabelGeometry(dev, data)
                canvas.drawColor(Color.rgb(226, 226, 230))
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                canvas.drawRect(ox, oy, ox + dev.widthMm * pxPerMm, oy + dev.heightMm * pxPerMm, paint)
                try {
                    painter.draw(canvas, geo, styles, pxPerMm, ox, oy, selected, showSelection = true)
                } catch (_: Exception) {
                    // 繪製失敗時至少保留白底，避免整個 Activity 因一次繪製異常崩潰
                    paint.style = Paint.Style.FILL; paint.color = Color.rgb(190, 45, 45)
                    paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(12).toFloat()
                    canvas.drawText("預覽繪製失敗", ox + dev.widthMm * pxPerMm / 2f, oy + dev.heightMm * pxPerMm / 2f, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = Color.rgb(170, 170, 175)
                canvas.drawRect(ox, oy, ox + dev.widthMm * pxPerMm, oy + dev.heightMm * pxPerMm, paint)
                drawRuler(canvas, dev)
            } catch (_: Exception) {
                // computeLayout 也可能因異常尺寸拋錯，保持灰底即可
            }
        }

        /** 每 10 mm 一道刻度與數字，方便確認實際尺寸。 */
        private fun drawRuler(canvas: Canvas, dev: LabelDevice) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.rgb(120, 120, 125)
            paint.textSize = dp(8).toFloat()
            paint.textAlign = Paint.Align.LEFT
            var mm = 0
            while (mm <= dev.widthMm.toInt()) {
                val x = ox + mm * pxPerMm
                val len = if (mm % 50 == 0) dp(7) else dp(4)
                canvas.drawLine(x, oy - len, x, oy, paint)
                if (mm % 20 == 0) {
                    paint.style = Paint.Style.FILL
                    canvas.drawText(mm.toString(), x + dp(1), oy - dp(8), paint)
                    paint.style = Paint.Style.STROKE
                }
                mm += 10
            }
            mm = 0
            paint.textAlign = Paint.Align.RIGHT
            while (mm <= dev.heightMm.toInt()) {
                val y = oy + mm * pxPerMm
                val len = if (mm % 50 == 0) dp(7) else dp(4)
                canvas.drawLine(ox - len, y, ox, y, paint)
                if (mm % 20 == 0) {
                    paint.style = Paint.Style.FILL
                    canvas.drawText(mm.toString(), ox - dp(2), y + dp(3), paint)
                    paint.style = Paint.Style.STROKE
                }
                mm += 10
            }
        }

        /** 依機型 mm × dpi 產生列印點陣（不含選取框與標尺）。 */
        fun renderBitmap(): Bitmap {
            val data = read?.invoke() ?: LabelData()
            val dev = canvasInfo?.invoke() ?: LabelDevice.P1
            val geo = LabelGeometry(dev, data)
            val out = Bitmap.createBitmap(dev.bitmapWidth, dev.bitmapHeight, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(Color.WHITE)
            painter.draw(c, geo, styles, dev.pxPerMm, 0f, 0f, null as LabelElement?, showSelection = false)
            return out
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val dev = canvasInfo?.invoke() ?: return true
            val data = read?.invoke() ?: return true
            val geo = LabelGeometry(dev, data)
            val toMm = { v: Float, origin: Float -> (v - origin) / pxPerMm }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    lastX = event.x; lastY = event.y
                    dragging = false; movedOnce = false
                    baseDistance = 0f
                    val hit = geo.hitTest(styles, toMm(event.x, ox), toMm(event.y, oy))
                    if (hit != null && hit != selected) onSelect?.invoke(hit)
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    baseDistance = distance(event)
                    baseScale = styles.getValue(selected).scale
                    onUndoSnapshot?.invoke()
                    movedOnce = true
                }

                MotionEvent.ACTION_MOVE -> {
                    val st = styles.getValue(selected)
                    if (event.pointerCount >= 2 && baseDistance > 0f) {
                        st.scale = (baseScale * distance(event) / baseDistance).coerceIn(0.3f, 3.5f)
                        geo.clamp(st, geo.base(selected))
                        movedOnce = true
                        invalidate()
                    } else {
                        val dx = (event.x - lastX) / pxPerMm
                        val dy = (event.y - lastY) / pxPerMm
                        if (!dragging && hypot(event.x - downX, event.y - downY) > dp(4)) {
                            dragging = true
                            onUndoSnapshot?.invoke()
                        }
                        if (dragging && (abs(dx) > 0f || abs(dy) > 0f)) {
                            st.dxMm += dx
                            st.dyMm += dy
                            // 拖曳時限制在標籤內，避免印出來被裁掉
                            geo.clamp(st, geo.base(selected))
                            lastX = event.x; lastY = event.y
                            movedOnce = true
                            invalidate()
                        }
                        lastX = event.x; lastY = event.y
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    baseDistance = 0f
                    if (movedOnce) onMoved?.invoke()
                    dragging = false
                }
            }
            return true
        }

        private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
    }
}
