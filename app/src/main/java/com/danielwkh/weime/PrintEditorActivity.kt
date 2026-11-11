package com.danielwkh.weime

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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

    private enum class PrintDevice(val label: String, val widthMm: Int, val heightMm: Int) {
        P1("P1／P1P  70 × 40 mm", 70, 40),
        DP40("DP40  180 × 100 mm", 180, 100)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "列印標籤"
        val draftPrefs = getSharedPreferences("kb", MODE_PRIVATE)
        val draftBarcode = draftPrefs.getString("printBarcode", "").orEmpty()
        val draftName = draftPrefs.getString("printName", "").orEmpty()
        val draftPrice = draftPrefs.getString("printPrice", "").orEmpty()
        val draftMemberPrice = draftPrefs.getString("printMemberPrice", "").orEmpty()
        val draftNonMemberPrice = draftPrefs.getString("printNonMemberPrice", "").orEmpty()
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
            setOnClickListener { showNotReady("列印連線功能下一階段接入 DT Android SDK") }
        }, LinearLayout.LayoutParams(dp(76), dp(44)))
        root.addView(header)

        preview = LabelPreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply {
            setMargins(dp(12), dp(12), dp(12), dp(8))
        })

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
        priceInput = field("價格", draftPrice)
        memberPriceInput = field("會員價", draftMemberPrice)
        nonMemberPriceInput = field("非會員價", draftNonMemberPrice)
        val hasMemberPrices = draftMemberPrice.isNotBlank() || draftNonMemberPrice.isNotBlank()
        memberMode.isChecked = hasMemberPrices
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

        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        sizeLabel = TextView(this).apply { text = "畫布：70 × 40 mm"; textSize = 14f; setTextColor(Color.DKGRAY) }
        actionRow.addView(sizeLabel, LinearLayout.LayoutParams(0, dp(48), 1f))
        actionRow.addView(Button(this).apply {
            text = "更新預覽"
            setOnClickListener { preview.invalidate() }
        }, LinearLayout.LayoutParams(dp(120), dp(48)))
        body.addView(actionRow)
        body.addView(Button(this).apply {
            text = "選擇印表機（尚未連線）"
            setOnClickListener { showNotReady("印表機搜尋功能下一階段接入 DT Android SDK") }
        })

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
            val barcodeScale: Int = 72
        )
        var read: (() -> LabelData)? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = read?.invoke() ?: LabelData("", "", "", "", "", false)
            canvas.drawColor(Color.rgb(232, 232, 235))
            val pad = dp(10).toFloat()
            val scale = min((width - pad * 2) / 7f, (height - pad * 2) / 4f)
            val w = 7f * scale
            val h = 4f * scale
            val left = (width - w) / 2f
            val top = (height - h) / 2f
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + w, top + h, paint)
            paint.color = Color.BLACK
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = scale * d.nameSize / 40f
            canvas.drawText(d.name.ifBlank { "商品名稱" }, left + w / 2, top + scale * 0.58f, paint)

            val encoded = BarcodeRenderer.encode(d.barcode)
            val bx = left + w * 0.08f
            val bw = min(w * d.barcodeScale / 100f, w * 0.84f)
            val by = top + h * 0.27f
            val bh = h * d.barcodeHeight / 100f
            if (encoded != null) {
                paint.style = Paint.Style.FILL
                val module = bw / encoded.bits.length
                for (i in encoded.bits.indices) {
                    if (encoded.bits[i] == '1') {
                        canvas.drawRect(bx + i * module, by, bx + (i + 1) * module, by + bh, paint)
                    }
                }
                paint.textSize = scale * d.barcodeLabelSize / 40f
                paint.typeface = android.graphics.Typeface.DEFAULT
                val humanReadable = encoded.display.takeLast(6)
                canvas.drawText(humanReadable, bx + bw / 2f, by + bh + scale * 0.25f, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.color = Color.rgb(190, 45, 45)
                canvas.drawRect(bx, by, bx + bw, by + bh, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = scale * 0.14f
                paint.typeface = android.graphics.Typeface.DEFAULT
                canvas.drawText("EAN-13／EAN-8 條碼需有效校驗碼", bx + bw / 2f, by + bh / 2f, paint)
                canvas.drawText(d.barcode.ifBlank { "請輸入 8、12 或 13 位條碼" }, bx + bw / 2f, by + bh + scale * 0.25f, paint)
                paint.color = Color.BLACK
            }

            paint.textSize = scale * d.priceSize / 40f
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (d.showMember) {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$${d.member.ifBlank { "0" }}", left + w - scale * 0.25f, top + h * 0.65f, paint)
                paint.textSize = scale * 0.28f
                paint.typeface = android.graphics.Typeface.DEFAULT
                canvas.drawText("會員價", left + w - scale * 2.0f, top + h * 0.64f, paint)
                paint.textSize = scale * d.priceSize / 40f
                paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
                canvas.drawText("$${d.nonMember.ifBlank { "0" }}", left + w - scale * 0.25f, top + h * 0.88f, paint)
                paint.textSize = scale * 0.28f
                paint.typeface = android.graphics.Typeface.DEFAULT
                canvas.drawText("非會員價", left + w - scale * 2.0f, top + h * 0.87f, paint)
            } else {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$${d.price.ifBlank { "0" }}", left + w * 0.74f, top + h * 0.82f, paint)
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.LTGRAY
            canvas.drawRect(left, top, left + w, top + h, paint)
        }
        private fun dp(v: Int) = (v * resources.displayMetrics.density)
    }
}
