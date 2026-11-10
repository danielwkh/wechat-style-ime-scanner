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
                memberPriceInput.text.toString(), nonMemberPriceInput.text.toString(), memberMode.isChecked
            )
        }
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        hint = hint
        setText(value)
        textSize = 16f
        singleLine = true
        setPadding(dp(12), 0, dp(12), 0)
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.LTGRAY)
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text; textSize = 16f; setTextColor(Color.DKGRAY); setPadding(0, dp(12), 0, dp(4))
    }

    private fun showNotReady(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class LabelPreviewView(context: Context) : View(context) {
        data class LabelData(val name: String, val barcode: String, val price: String, val member: String, val nonMember: String, val showMember: Boolean)
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
            paint.textSize = scale * 0.34f
            canvas.drawText(d.name.ifBlank { "商品名稱" }, left + w / 2, top + scale * 0.58f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, scale * 0.025f)
            val bx = left + scale * 0.35f; val by = top + scale * 1.25f; val bw = scale * 2.55f; val bh = scale * 1.05f
            val code = d.barcode.ifBlank { "123456" }
            for (i in code.indices) if (code[i].isDigit()) {
                val x = bx + bw * i / code.length
                paint.strokeWidth = if ((code[i] - '0') % 3 == 0) scale * 0.055f else scale * 0.025f
                canvas.drawLine(x, by, x, by + bh, paint)
            }
            paint.style = Paint.Style.FILL; paint.textSize = scale * 0.22f; paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText(code, bx + bw / 2, by + bh + scale * 0.28f, paint)
            paint.textSize = scale * 1.45f; paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (d.showMember) {
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$${d.member.ifBlank { "0" }}", left + w - scale * 0.35f, top + scale * 2.55f, paint)
                paint.textSize = scale * 0.42f; paint.typeface = android.graphics.Typeface.DEFAULT
                canvas.drawText("會員價", left + w - scale * 2.05f, top + scale * 2.52f, paint)
                canvas.drawText("非會員價 $${d.nonMember.ifBlank { "0" }}", left + w - scale * 0.35f, top + scale * 3.15f, paint)
            } else {
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$${d.price.ifBlank { "0" }}", left + w * 0.72f, top + scale * 3.0f, paint)
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = Color.LTGRAY
            canvas.drawRect(left, top, left + w, top + h, paint)
        }
        private fun dp(v: Int) = (v * resources.displayMetrics.density)
    }
}
