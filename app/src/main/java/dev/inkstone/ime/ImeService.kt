package dev.inkstone.ime

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkstone.ime.scanner.ScannerActivity

class ImeService : InputMethodService() {
    private lateinit var keyboard: LinearLayout
    private lateinit var candidateBar: LinearLayout
    private lateinit var composingLabel: TextView
    private val composing = StringBuilder()
    private var symbols = false
    private var english = false
    private var uppercase = false

    override fun onCreateInputView(): View {
        keyboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(8))
            setBackgroundColor(Color.rgb(239, 241, 247))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        composingLabel = TextView(this).apply {
            setTextColor(Color.rgb(65, 77, 116))
            textSize = 15f
            setPadding(dp(8), 0, dp(4), 0)
            typeface = Typeface.DEFAULT_BOLD
        }
        toolbar.addView(composingLabel, LinearLayout.LayoutParams(0, dp(42), 1f))
        toolbar.addView(toolButton("掃描") { launchScanner() })
        toolbar.addView(toolButton("中") {
            english = !english
            rebuildKeyboard()
        })
        keyboard.addView(toolbar)

        candidateBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(3))
        }
        keyboard.addView(candidateBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
        ))
        addKeyRows()
        refresh()
        return keyboard
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.clear()
        refresh()
    }

    private fun addKeyRows() {
        if (symbols) {
            addRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"))
            addRow(listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/"))
            addRow(listOf("ABC", ".", ",", "?", "!", "'", "⌫"))
        } else {
            addRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
            addRow(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
            addRow(listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"))
        }

        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        bottom.addView(toolButton(if (symbols) "ABC" else "?123") {
            symbols = !symbols
            rebuildKeyboard()
        }, weight(1.15f))
        bottom.addView(toolButton(",") { commitLiteral(",") }, weight(0.8f))
        bottom.addView(toolButton("空白") { handleSpace() }, weight(4.6f))
        bottom.addView(toolButton(".") { commitLiteral(".") }, weight(0.8f))
        bottom.addView(toolButton(actionLabel()) { handleEnter() }, weight(1.15f))
        keyboard.addView(bottom, rowParams())
    }

    private fun rebuildKeyboard() {
        keyboard.removeViews(2, keyboard.childCount - 2)
        addKeyRows()
        refresh()
    }

    private fun addRow(keys: List<String>) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        keys.forEach { key ->
            val displayedKey = if (
                english && !symbols && key.length == 1 && key[0].isLetter()
            ) key.uppercase() else key
            row.addView(
                keyButton(displayedKey),
                weight(if (key == "⇧" || key == "⌫" || key == "ABC") 1.35f else 1f)
            )
        }
        keyboard.addView(row, rowParams())
    }

    private fun keyButton(label: String): Button = Button(this).apply {
        text = label
        textSize = if (label.length > 1) 14f else 19f
        isAllCaps = false
        setTextColor(Color.rgb(44, 48, 61))
        background = roundedBackground(Color.WHITE)
        minimumWidth = 0
        minWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { onKey(label) }
    }

    private fun toolButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.rgb(50, 59, 92))
        background = roundedBackground(Color.rgb(222, 226, 239))
        minimumWidth = 0
        minWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { action() }
    }

    private fun onKey(key: String) {
        when (key) {
            "⌫" -> backspace()
            "⇧" -> {
                uppercase = !uppercase
                rebuildKeyboard()
            }
            "ABC" -> {
                symbols = false
                rebuildKeyboard()
            }
            else -> {
                if (english || symbols) {
                    commitLiteral(if (english && uppercase) key.uppercase() else key)
                }
                else {
                    composing.append(key.lowercase())
                    refresh()
                }
            }
        }
    }

    private fun handleSpace() {
        if (composing.isNotEmpty()) {
            val candidate = PinyinDictionary.candidates(composing.toString()).firstOrNull()
            if (candidate != null) commitCandidate(candidate) else commitComposition()
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
    }

    private fun handleEnter() {
        val connection = currentInputConnection ?: return
        commitComposition()
        val options = currentInputEditorInfo?.imeOptions ?: 0
        val action = options and EditorInfo.IME_MASK_ACTION
        if (action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            options and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0
        ) {
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
        }
    }

    private fun backspace() {
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.lastIndex)
            refresh()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun commitCandidate(candidate: String) {
        currentInputConnection?.commitText(candidate, 1)
        composing.clear()
        refresh()
    }

    private fun commitComposition() {
        if (composing.isNotEmpty()) {
            currentInputConnection?.commitText(composing.toString(), 1)
            composing.clear()
            refresh()
        }
    }

    private fun commitLiteral(text: String) {
        commitComposition()
        currentInputConnection?.commitText(text, 1)
    }

    private fun refresh() {
        if (!::candidateBar.isInitialized) return
        composingLabel.text = composing.toString()
        candidateBar.removeAllViews()
        PinyinDictionary.candidates(composing.toString()).forEach { candidate ->
            val item = TextView(this).apply {
                text = candidate
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(38, 43, 57))
                setOnClickListener { commitCandidate(candidate) }
            }
            candidateBar.addView(item, LinearLayout.LayoutParams(0, dp(38), 1f))
        }
        val languageButton = (keyboard.getChildAt(0) as? LinearLayout)?.getChildAt(2) as? Button
        languageButton?.text = if (english) "EN" else "中"
    }

    private fun launchScanner() {
        startActivity(Intent(this, ScannerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun actionLabel(): String = when (
        currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
    ) {
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_SEARCH -> "搜尋"
        EditorInfo.IME_ACTION_SEND -> "傳送"
        EditorInfo.IME_ACTION_NEXT -> "下一個"
        else -> "換行"
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(47)
    ).apply { bottomMargin = dp(5) }

    private fun weight(value: Float) = LinearLayout.LayoutParams(0, dp(43), value).apply {
        setMargins(dp(2), 0, dp(2), 0)
    }

    private fun roundedBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(7).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile
        private var activeService: ImeService? = null

        fun commitScannedText(text: String): Boolean {
            val connection = activeService?.currentInputConnection ?: return false
            connection.commitText(text, 1)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }
}
