package com.danielwkh.weime

import android.app.Activity
import android.Manifest
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.dothantech.printer.IDzPrinter.PrintProgress
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.dothantech.printer.IDzPrinter.PrinterState
import kotlin.math.hypot
import kotlin.math.min

/**
 * 第一階段列印編輯器：先完成模板、欄位及本地預覽 UI；LPAPI 連線與送印留待後續整合。
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
    private var device = PrintDevice.P1
    private var nameSize = 28
    private var priceSize = 58
    private var barcodeLabelSize = 17
    private var barcodeHeight = 58
    private var barcodeScale = 72
    private lateinit var printerManager: DtPrinterManager
    private lateinit var printerButton: Button
    private val discoveredPrinters = LinkedHashMap<String, PrinterAddress>()
    private var printCopies = 1
    private var printDirection = 0
    private var printGapLength = 3
    private var printDensity = -1
    private var printSpeed = -1

    private enum class PrintDevice(val label: String, val widthMm: Int, val heightMm: Int) {
        P1("P1／P1P  70 × 40 mm", 70, 40),
        DP40("DP40  180 × 100 mm", 180, 100)
    }

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
                    when (state) {
                        PrinterState.Connected, PrinterState.Connected2 -> {
                            printerButton.text = "已連線：${printerManager.printerName()}"
                            toast("印表機連線成功")
                        }
                        PrinterState.Disconnected -> {
                            printerButton.text = "選擇印表機"
                            toast("印表機已斷線")
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
        val draftPrefs = getSharedPreferences("kb", MODE_PRIVATE)
        val draftBarcode = draftPrefs.getString("printBarcode", "").orEmpty()
        val draftName = draftPrefs.getString("printName", "").orEmpty()
        val draftPrice = draftPrefs.getString("printPrice", "").orEmpty()
        val draftMemberPrice = draftPrefs.getString("printMemberPrice", "").orEmpty()
        val draftNonMemberPrice = draftPrefs.getString("printNonMemberPrice", "").orEmpty()
        val effectivePrice = draftPrice.ifBlank { draftNonMemberPrice }
            .ifBlank { draftPrice }
        val layoutPrefs = getSharedPreferences("printLayout", MODE_PRIVATE)
        nameSize = layoutPrefs.getInt("nameSize", nameSize)
        priceSize = layoutPrefs.getInt("priceSize", priceSize)
        barcodeLabelSize = layoutPrefs.getInt("barcodeLabelSize", barcodeLabelSize)
        barcodeHeight = layoutPrefs.getInt("barcodeHeight", barcodeHeight)
        barcodeScale = layoutPrefs.getInt("barcodeScale", barcodeScale)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 245, 247))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        header.addView(Button(this).apply {
            text = "‹ 返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(92), dp(44)))
        header.addView(TextView(this).apply {
            text = "標籤預覽與編輯"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(Button(this).apply {
            text = "列印"
            setOnClickListener { printCurrentLabel() }
        }, LinearLayout.LayoutParams(dp(76), dp(44)))
        root.addView(header)

        preview = LabelPreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply {
            setMargins(dp(12), dp(12), dp(12), dp(8))
        })
        root.addView(TextView(this).apply {
            text = "點選商品名、條碼或價格後拖曳；雙指只可縮放"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }, LinearLayout.LayoutParams(-1, dp(28)))
        val canvasTools = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
        }
        val toolRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        fun canvasButton(label: String, click: () -> Unit) = Button(this).apply {
            text = label
            textSize = 12f
            setOnClickListener { click(); preview.invalidate() }
        }
        toolRow.addView(canvasButton("左對齊") { preview.alignSelected("left") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow.addView(canvasButton("水平置中") { preview.alignSelected("center") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow.addView(canvasButton("右對齊") { preview.alignSelected("right") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        canvasTools.addView(toolRow)
        val toolRow2 = LinearLayout(this).apply { gravity = Gravity.CENTER }
        toolRow2.addView(canvasButton("頂端") { preview.alignSelected("top") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow2.addView(canvasButton("垂直置中") { preview.alignSelected("middle") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow2.addView(canvasButton("底端") { preview.alignSelected("bottom") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolRow2.addView(canvasButton("反色選取") {
            preview.toggleInvertSelected()
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        canvasTools.addView(toolRow2)
        root.addView(canvasTools, LinearLayout.LayoutParams(-1, dp(88)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(20))
        }
        body.addView(sectionTitle("1. 選擇印表機尺寸"))
        val deviceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@PrintEditorActivity).apply { text = PrintDevice.P1.label; isChecked = true; tag = PrintDevice.P1 })
            addView(RadioButton(this@PrintEditorActivity).apply { text = "P1P  70 × 40 mm（300 dpi）"; tag = PrintDevice.P1 })
            addView(RadioButton(this@PrintEditorActivity).apply { text = PrintDevice.DP40.label; tag = PrintDevice.DP40 })
            setOnCheckedChangeListener { group, checkedId ->
                device = group.findViewById<RadioButton>(checkedId).tag as PrintDevice
                sizeLabel.text = "畫布：${device.widthMm} × ${device.heightMm} mm"
                preview.invalidate()
            }
        }
        body.addView(deviceGroup)

        body.addView(sectionTitle("2. 選擇模板"))
        val template = Spinner(this)
        template.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("單一價格", "會員價／非會員價"))
        template.setSelection(0)
        template.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 1) memberMode.isChecked = true
            }
        }
        body.addView(template, LinearLayout.LayoutParams(-1, dp(48)))
        memberMode = CheckBox(this).apply {
            text = "顯示會員價／非會員價區塊"
            setOnCheckedChangeListener { _, checked ->
                memberPriceInput.visibility = if (checked) View.VISIBLE else View.GONE
                nonMemberPriceInput.visibility = if (checked) View.VISIBLE else View.GONE
                preview.invalidate()
            }
        }
        body.addView(memberMode)

        body.addView(sectionTitle("3. 編輯內容"))
        nameInput = field("商品名稱", draftName)
        barcodeInput = field("條碼", draftBarcode)
        barcodeInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        priceInput = field("價格／非會員價", effectivePrice)
        memberPriceInput = field("會員價", draftMemberPrice)
        nonMemberPriceInput = field("非會員價", draftNonMemberPrice.ifBlank { effectivePrice })
        val hasMemberPrices = draftMemberPrice.isNotBlank() || draftNonMemberPrice.isNotBlank()
        memberMode.isChecked = hasMemberPrices
        template.setSelection(if (hasMemberPrices) 1 else 0)
        memberPriceInput.visibility = if (hasMemberPrices) View.VISIBLE else View.GONE
        nonMemberPriceInput.visibility = if (hasMemberPrices) View.VISIBLE else View.GONE
        body.addView(nameInput)
        body.addView(barcodeInput)
        body.addView(priceInput)
        body.addView(memberPriceInput)
        body.addView(nonMemberPriceInput)

        body.addView(sectionTitle("4. 調整版面元素大小"))
        body.addView(sizeControl("商品名字體", 12, 40, nameSize) { nameSize = it; saveLayout(); preview.invalidate() })
        body.addView(sizeControl("價格字體", 20, 86, priceSize) { priceSize = it; saveLayout(); preview.invalidate() })
        body.addView(sizeControl("條碼數字字體", 10, 30, barcodeLabelSize) { barcodeLabelSize = it; saveLayout(); preview.invalidate() })
        body.addView(sizeControl("條碼高度", 25, 90, barcodeHeight) { barcodeHeight = it; saveLayout(); preview.invalidate() })
        body.addView(sizeControl("條碼寬度", 40, 100, barcodeScale) { barcodeScale = it; saveLayout(); preview.invalidate() })

        body.addView(sectionTitle("5. 列印參數"))
        val copiesInput = field("列印份數", printCopies.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) printCopies = text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
            }
        }
        body.addView(copiesInput)
        val gapInput = field("間隔長度（mm）", printGapLength.toString()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) printGapLength = text.toString().toIntOrNull()?.coerceIn(0, 50) ?: 3
            }
        }
        body.addView(gapInput)
        val direction = Spinner(this).apply {
            adapter = ArrayAdapter(this@PrintEditorActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("出紙方向：0°", "出紙方向：90°", "出紙方向：180°", "出紙方向：270°"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    printDirection = position * 90
                }
            }
        }
        body.addView(direction, LinearLayout.LayoutParams(-1, dp(48)))
        body.addView(sizeControl("列印濃度", 0, 20, if (printDensity < 0) 0 else printDensity) {
            printDensity = if (it == 0) -1 else it
        })
        body.addView(sizeControl("列印速度", 0, 5, if (printSpeed < 0) 0 else printSpeed) {
            printSpeed = if (it == 0) -1 else it
        })

        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sizeLabel = TextView(this).apply { text = "畫布：70 × 40 mm"; textSize = 14f; setTextColor(Color.DKGRAY) }
        actionRow.addView(sizeLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        actionRow.addView(Button(this).apply {
            text = "更新預覽"
            setOnClickListener { preview.invalidate() }
        }, LinearLayout.LayoutParams(dp(120), dp(48)))
        body.addView(actionRow)
        printerButton = Button(this).apply {
            text = "選擇印表機"
            setOnClickListener { discoverPrinters() }
        }
        body.addView(printerButton)

        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        installPreviewBinding()
    }

    private fun installPreviewBinding() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { preview.invalidate() }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        listOf(nameInput, barcodeInput, priceInput, memberPriceInput, nonMemberPriceInput).forEach { it.addTextChangedListener(watcher) }
        preview.read = {
            LabelPreviewView.LabelData(
                nameInput.text.toString(), barcodeInput.text.toString(), priceInput.text.toString(),
                memberPriceInput.text.toString(), nonMemberPriceInput.text.toString(), memberMode.isChecked,
                nameSize, priceSize, barcodeLabelSize, barcodeHeight, barcodeScale
            )
        }
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        setHint(hint)
        setText(value)
        textSize = 16f
        setSingleLine(true)
        setPadding(dp(12), 0, dp(12), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.LTGRAY)
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f; setTextColor(Color.DKGRAY); setPadding(0, dp(12), 0, dp(4))
    }

    private fun sizeControl(label: String, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val value = TextView(this).apply {
            text = "$label：$initial"
            textSize = 14f
            setTextColor(Color.DKGRAY)
        }
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val current = min + progress
                    value.text = "$label：$current"
                    onChange(current)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        row.addView(value, LinearLayout.LayoutParams(dp(112), dp(48)))
        row.addView(seek, LinearLayout.LayoutParams(0, dp(48), 1f))
        return row
    }

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
        printerButton.text = "搜尋印表機中…"
        printerManager.discover()
        Handler(Looper.getMainLooper()).postDelayed({ showPrinterChoices() }, 1800)
    }

    private fun showPrinterChoices() {
        printerManager.stopDiscovery()
        val entries = discoveredPrinters.values.toList()
        if (entries.isEmpty()) {
            printerButton.text = "選擇印表機"
            toast("找不到印表機，請確認印表機已開機並靠近手機")
            return
        }
        val labels = entries.map { it.shownName?.takeIf(String::isNotBlank) ?: it.macAddress.orEmpty() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("選擇印表機")
            .setItems(labels) { _, which ->
                val selected = entries[which]
                printerButton.text = "連線中…"
                if (!printerManager.connect(selected)) {
                    printerButton.text = "選擇印表機"
                    toast("無法提交連線要求")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun printCurrentLabel() {
        if (!printerManager.isConnected()) {
            toast("請先選擇並連線印表機")
            discoverPrinters()
            return
        }
        val bitmap = preview.renderBitmap()
        val submitted = printerManager.print(
            bitmap = bitmap,
            copies = printCopies,
            direction = printDirection,
            gapLength = printGapLength,
            density = printDensity,
            speed = printSpeed,
        )
        if (!submitted) toast("列印要求未能送出")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            discoverPrinters()
        }
    }

    override fun onDestroy() {
        if (::printerManager.isInitialized) printerManager.close()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun showNotReady(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    private fun saveLayout() {
        getSharedPreferences("printLayout", MODE_PRIVATE).edit()
            .putInt("nameSize", nameSize)
            .putInt("priceSize", priceSize)
            .putInt("barcodeLabelSize", barcodeLabelSize)
            .putInt("barcodeHeight", barcodeHeight)
            .putInt("barcodeScale", barcodeScale)
            .apply()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class LabelPreviewView(context: Context) : View(context) {
        enum class Element { NAME, BARCODE, PRICE }
        data class LabelData(
            val name: String,
            val barcode: String,
            val price: String,
            val member: String,
            val nonMember: String,
            val showMember: Boolean,
            val nameSize: Int = 28,
            val priceSize: Int = 58,
            val barcodeLabelSize: Int = 17,
            val barcodeHeight: Int = 58,
            val barcodeScale: Int = 72,
        )
        private data class Transform(var dx: Float = 0f, var dy: Float = 0f, var scale: Float = 1f, var invert: Boolean = false)
        var read: (() -> LabelData)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val transforms = Element.values().associateWith { Transform() }.toMutableMap()
        private var selected = Element.NAME
        private var lastX = 0f
        private var lastY = 0f
        private var baseDistance = 1f
        private var baseScale = 1f
        private var lastCanvas = floatArrayOf(0f, 0f, 0f, 0f)
        private var lastRects: Map<Element, FloatArray> = emptyMap()
        private var showSelection = true

        fun renderBitmap(): Bitmap {
            val output = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val outputCanvas = Canvas(output)
            showSelection = false
            draw(outputCanvas)
            showSelection = true
            return output
        }

        fun toggleInvertSelected() {
            val tr = transforms.getValue(selected)
            tr.invert = !tr.invert
            invalidate()
        }

        fun alignSelected(mode: String) {
            val rect = lastRects[selected] ?: return
            val canvas = lastCanvas
            val tr = transforms.getValue(selected)
            val cx = (rect[0] + rect[2]) / 2f
            val cy = (rect[1] + rect[3]) / 2f
            when (mode) {
                "left" -> tr.dx = canvas[0] + (rect[2] - rect[0]) / 2f - cx
                "center" -> tr.dx = (canvas[0] + canvas[2]) / 2f - cx
                "right" -> tr.dx = canvas[2] - (rect[2] - rect[0]) / 2f - cx
                "top" -> tr.dy = canvas[1] + (rect[3] - rect[1]) / 2f - cy
                "middle" -> tr.dy = (canvas[1] + canvas[3]) / 2f - cy
                "bottom" -> tr.dy = canvas[3] - (rect[3] - rect[1]) / 2f - cy
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = read?.invoke() ?: LabelData("", "", "", "", "", false)
            canvas.drawColor(Color.rgb(232, 232, 235))
            val pad = dp(10f)
            val scale = min((width - pad * 2) / 7f, (height - pad * 2) / 4f)
            val w = 7f * scale
            val h = 4f * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            lastCanvas = floatArrayOf(left, top, left + w, top + h)
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + w, top + h, paint)
            paint.color = Color.BLACK

            val nameRect = floatArrayOf(left + scale * 0.35f, top + scale * 0.18f, left + w - scale * 0.35f, top + scale * 0.82f)
            val encoded = BarcodeRenderer.encode(d.barcode)
            val bx = left + w * 0.08f
            val bw = min(w * d.barcodeScale / 100f, w * 0.84f)
            val by = top + h * 0.27f
            val requestedBarcodeHeight = h * d.barcodeHeight / 100f
            val barcodeLabelSizePx = scale * d.barcodeLabelSize / 40f
            paint.textSize = barcodeLabelSizePx
            val labelMetrics = paint.fontMetrics
            val labelGap = scale * 0.08f
            val labelReserve = maxOf(
                scale * 0.58f,
                labelGap + (labelMetrics.descent - labelMetrics.ascent) + scale * 0.12f
            )
            val bh = min(requestedBarcodeHeight, (top + h - by - labelReserve).coerceAtLeast(scale * 0.35f))
            val barcodeLabelBaseline = by + bh + labelGap - labelMetrics.ascent
            val barcodeRect = floatArrayOf(bx, by, bx + bw, by + bh + labelReserve)
            val priceRect = floatArrayOf(left + w * 0.48f, top + h * 0.48f, left + w - scale * 0.15f, top + h - scale * 0.12f)
            lastRects = mapOf(Element.NAME to nameRect, Element.BARCODE to barcodeRect, Element.PRICE to priceRect)

            withTransform(canvas, Element.NAME, nameRect[0], nameRect[1], nameRect[2], nameRect[3]) {
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                paint.textSize = scale * d.nameSize / 40f
                canvas.drawText(d.name.ifBlank { "商品名稱" }, (nameRect[0] + nameRect[2]) / 2f, top + scale * 0.58f, paint)
            }
            withTransform(canvas, Element.BARCODE, barcodeRect[0], barcodeRect[1], barcodeRect[2], barcodeRect[3]) {
                if (encoded != null) {
                    paint.style = Paint.Style.FILL
                    val module = bw / encoded.bits.length
                    for (i in encoded.bits.indices) if (encoded.bits[i] == '1') {
                        canvas.drawRect(bx + i * module, by, bx + (i + 1) * module, by + bh, paint)
                    }
                    paint.textSize = barcodeLabelSizePx
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    canvas.drawText(encoded.display.takeLast(6), bx + bw / 2f, barcodeLabelBaseline, paint)
                } else {
                    paint.style = Paint.Style.STROKE; paint.color = Color.rgb(190, 45, 45)
                    canvas.drawRect(bx, by, bx + bw, by + bh, paint)
                    paint.style = Paint.Style.FILL; paint.textSize = scale * 0.14f
                    paint.typeface = android.graphics.Typeface.DEFAULT
                    canvas.drawText("條碼格式無效", bx + bw / 2f, by + bh / 2f, paint)
                    paint.color = Color.BLACK
                }
            }
            withTransform(canvas, Element.PRICE, priceRect[0], priceRect[1], priceRect[2], priceRect[3]) {
                paint.textSize = scale * d.priceSize / 40f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                if (d.showMember) {
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(d.member.ifBlank { "—" }.let { if (it == "—") it else "$${it}" }, left + w - scale * 0.25f, top + h * 0.65f, paint)
                    paint.textSize = scale * 0.28f; paint.typeface = android.graphics.Typeface.DEFAULT
                    canvas.drawText("會員價", left + w - scale * 2.0f, top + h * 0.64f, paint)
                    paint.textSize = scale * d.priceSize / 40f; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                    canvas.drawText(d.nonMember.ifBlank { "—" }.let { if (it == "—") it else "$${it}" }, left + w - scale * 0.25f, top + h * 0.88f, paint)
                    paint.textSize = scale * 0.28f; paint.typeface = android.graphics.Typeface.DEFAULT
                    canvas.drawText("非會員價", left + w - scale * 2.0f, top + h * 0.87f, paint)
                } else {
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText(d.price.ifBlank { "—" }.let { if (it == "—") it else "$${it}" }, left + w * 0.74f, top + h * 0.82f, paint)
                }
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.LTGRAY
            canvas.drawRect(left, top, left + w, top + h, paint)
            if (showSelection) drawSelection(canvas, selected, nameRect, barcodeRect, priceRect)
        }

        private fun withTransform(canvas: Canvas, element: Element, l: Float, t: Float, r: Float, b: Float, draw: () -> Unit) {
            val tr = transforms.getValue(element)
            val cx = (l + r) / 2f; val cy = (t + b) / 2f
            canvas.save()
            canvas.translate(tr.dx, tr.dy)
            canvas.scale(tr.scale, tr.scale, cx, cy)
            if (tr.invert) {
                paint.style = Paint.Style.FILL
                paint.color = Color.BLACK
                canvas.drawRect(l, t, r, b, paint)
                paint.color = Color.WHITE
            } else {
                paint.color = Color.BLACK
            }
            draw()
            canvas.restore()
        }

        private fun drawSelection(canvas: Canvas, element: Element, name: FloatArray, barcode: FloatArray, price: FloatArray) {
            val rect = when (element) { Element.NAME -> name; Element.BARCODE -> barcode; Element.PRICE -> price }
            val tr = transforms.getValue(element)
            canvas.save(); canvas.translate(tr.dx, tr.dy)
            canvas.scale(tr.scale, tr.scale, (rect[0] + rect[2]) / 2f, (rect[1] + rect[3]) / 2f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f); paint.color = Color.rgb(40, 120, 230)
            canvas.drawRect(rect[0], rect[1], rect[2], rect[3], paint)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val data = read?.invoke() ?: return true
            val pad = dp(10f); val s = min((width - pad * 2) / 7f, (height - pad * 2) / 4f); val w = 7f * s; val h = 4f * s
            val l = (width - w) / 2f; val t = (height - h) / 2f
            val barcodeTop = t + h * 0.27f
            val barcodeLabelSizePx = s * data.barcodeLabelSize / 40f
            val labelReserve = maxOf(s * 0.58f, barcodeLabelSizePx * 1.5f)
            val barcodeHeight = min(h * data.barcodeHeight / 100f, (t + h - barcodeTop - labelReserve).coerceAtLeast(s * 0.35f))
            val barcode = floatArrayOf(l + w * 0.08f, barcodeTop, l + w * 0.08f + min(w * data.barcodeScale / 100f, w * 0.84f), barcodeTop + barcodeHeight + labelReserve)
            val hitElement = hit(event.x, event.y, l + s * 0.35f, t + s * 0.18f, l + w - s * 0.35f, t + s * 0.82f, barcode, l + w * 0.48f, t + h * 0.48f, l + w - s * 0.15f, t + h - s * 0.12f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { selected = hitElement; lastX = event.x; lastY = event.y; invalidate() }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    baseDistance = distance(event); val tr = transforms.getValue(selected); baseScale = tr.scale
                }
                MotionEvent.ACTION_MOVE -> {
                    val tr = transforms.getValue(selected)
                    if (event.pointerCount >= 2 && baseDistance > 0f) { tr.scale = (baseScale * distance(event) / baseDistance).coerceIn(0.4f, 3f) }
                    else { tr.dx += event.x - lastX; tr.dy += event.y - lastY }
                    lastX = event.x; lastY = event.y; invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { baseDistance = 0f; invalidate() }
            }
            return true
        }

        private fun hit(x: Float, y: Float, nl: Float, nt: Float, nr: Float, nb: Float, b: FloatArray, pl: Float, pt: Float, pr: Float, pb: Float): Element {
            return when { x in b[0]..b[2] && y in b[1]..b[3] -> Element.BARCODE; x in pl..pr && y in pt..pb -> Element.PRICE; x in nl..nr && y in nt..nb -> Element.NAME; else -> selected }
        }
        private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
