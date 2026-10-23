package com.danielwkh.weime

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 整個鍵盤的容器：長按任何位置後，拖動即可移動游標（左右＝左右，上下＝上下行）。 */
class KeyboardRoot(
    ctx: Context,
    private val stepX: Int,
    private val stepY: Int,
    private val onStart: () -> Unit,
    private val onMove: (dx: Int, dy: Int) -> Unit,
    private val onEnd: () -> Unit
) : LinearLayout(ctx) {
    var suppress = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var accX = 0f
    private var accY = 0f
    private var longPressed = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val longRun = Runnable {
        if (suppress) return@Runnable
        longPressed = true
        accX = 0f
        accY = 0f
        cancelChildren()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onStart()
    }

    private fun cancelChildren() {
        val now = SystemClock.uptimeMillis()
        val c = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(c)
        c.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                suppress = false
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                longPressed = false
                removeCallbacks(longRun)
                postDelayed(longRun, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) {
                    accX += ev.x - lastX
                    accY += ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    while (accX >= stepX) { onMove(1, 0); accX -= stepX }
                    while (accX <= -stepX) { onMove(-1, 0); accX += stepX }
                    while (accY >= stepY) { onMove(0, 1); accY -= stepY }
                    while (accY <= -stepY) { onMove(0, -1); accY += stepY }
                    return true
                }
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(longRun)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRun)
                if (longPressed) {
                    longPressed = false
                    onEnd()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}

class ImeService : InputMethodService() {
    companion object {
        @Volatile var pending: String? = null
    }

    private var mode = 0            // 0 中文拼音, 1 英文, 2 符號
    private var lastLetterMode = 0
    private var shift = false
    private val comp = StringBuilder()
    private var cands: List<Pair<String, Int>> = emptyList()
    private lateinit var kbRoot: KeyboardRoot
    private lateinit var candBar: LinearLayout
    private lateinit var keyArea: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kb", Context.MODE_PRIVATE) }

    private val ic: InputConnection? get() = currentInputConnection
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 每次按鍵都記錄目前使用的鍵盤（中／英／符號、Shift），下次顯示時還原。 */
    private fun saveState() {
        prefs.edit().putInt("mode", mode).putInt("last", lastLetterMode).putBoolean("shift", shift).apply()
    }

    override fun onCreate() {
        super.onCreate()
        mode = prefs.getInt("mode", 0)
        lastLetterMode = prefs.getInt("last", 0)
        shift = prefs.getBoolean("shift", false)
        if (mode !in 0..2) mode = 0
        if (lastLetterMode !in 0..1) lastLetterMode = 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        kbRoot = KeyboardRoot(
            this, dp(16), dp(34),
            onStart = { commitAll(); keyArea.alpha = 0.35f },
            onMove = { dx, dy -> moveCursor(dx, dy) },
            onEnd = { keyArea.alpha = 1f }
        ).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EDEDED"))
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        candBar = LinearLayout(this)
        scroll.addView(candBar)
        top.addView(scroll, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(
            cell("🎤", 18f, "#EDEDED") { startVoice() },
            LinearLayout.LayoutParams(dp(48), dp(38)).apply { setMargins(dp(4), 0, 0, 0) }
        )
        top.addView(
            cell("掃描", 15f, "#07C160", Color.WHITE) { startScan() },
            LinearLayout.LayoutParams(dp(64), dp(38)).apply { setMargins(dp(4), 0, dp(6), 0) }
        )
        keyArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(6))
        }
        kbRoot.addView(top)
        kbRoot.addView(keyArea)
        render()
        showCands()
        return kbRoot
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        comp.clear()
        cands = emptyList()
        if (::candBar.isInitialized) showCands()
        pending?.let {
            currentInputConnection?.commitText(it, 1)
            pending = null
            // 掃描／語音返回後，自動把同一個鍵盤重新顯示出來
            handler.postDelayed({ requestShowSelf(0) }, 200)
        }
    }

    // ---------- UI ----------
    private fun cell(t: String, size: Float, bg: String, fg: Int = Color.BLACK, click: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(fg)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bg))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener {
                click()
                saveState()
            }
        }

    private val repeatDel = object : Runnable {
        override fun run() {
            onBackspace()
            handler.postDelayed(this, 60)
        }
    }

    /** 刪除鍵：按住會連續刪除。 */
    private fun delKey(): TextView = cell("⌫", 18f, "#D0D0D0") {}.apply {
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    kbRoot.suppress = true
                    v.alpha = 0.6f
                    onBackspace()
                    handler.postDelayed(repeatDel, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    handler.removeCallbacks(repeatDel)
                }
            }
            true
        }
    }

    private fun add(row: LinearLayout, v: View, w: Float) {
        row.addView(v, LinearLayout.LayoutParams(0, dp(48), w).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })
    }

    private fun render() {
        keyArea.removeAllViews()
        val rows = if (mode == 2) listOf("1234567890", "@#\$%&*-+=/", "()「」_~…")
        else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val zh = if (mode == 2) lastLetterMode == 0 else mode == 0

        for ((i, r) in rows.withIndex()) {
            val line = LinearLayout(this)
            if (i == 1 && mode != 2) add(line, View(this), 0.5f)
            if (i == 2) {
                if (mode == 1) add(line, cell("⇧", 18f, if (shift) "#FFFFFF" else "#D0D0D0") {
                    shift = !shift
                    render()
                }, 1.5f)
                else add(line, View(this), 1.5f)
            }
            for (ch in r) {
                val label = if (mode == 1 && shift) ch.uppercase() else ch.toString()
                add(line, cell(label, 20f, "#FFFFFF") { onChar(ch) }, 1f)
            }
            if (i == 1 && mode != 2) add(line, View(this), 0.5f)
            if (i == 2) add(line, delKey(), 1.5f)   // 刪除鍵固定在第三行最右邊
            keyArea.addView(line)
        }

        val b = LinearLayout(this)
        if (mode == 2) {
            add(b, cell("ABC", 15f, "#D0D0D0") { mode = lastLetterMode; render(); showCands() }, 1.5f)
        } else {
            add(b, cell("123", 15f, "#D0D0D0") {
                commitAll()
                lastLetterMode = mode
                mode = 2
                shift = false
                render()
            }, 1.5f)
            add(b, cell(if (mode == 0) "中" else "EN", 15f, "#D0D0D0") {
                commitAll()
                mode = 1 - mode
                shift = false
                render()
            }, 1.5f)
        }
        val comma = if (zh) "，" else ","
        val period = if (zh) "。" else "."
        add(b, cell(comma, 20f, "#FFFFFF") { onPunc(comma) }, 1f)
        add(b, cell("空格", 15f, "#FFFFFF") { onSpace() }, 4f)
        add(b, cell(period, 20f, "#FFFFFF") { onPunc(period) }, 1f)
        add(b, cell("⏎", 18f, "#07C160", Color.WHITE) { onEnter() }, 1.5f)
        keyArea.addView(b)
    }

    private fun showCands() {
        candBar.removeAllViews()
        for ((i, c) in cands.withIndex()) {
            candBar.addView(TextView(this).apply {
                text = c.first
                textSize = 20f
                setTextColor(if (i == 0) Color.parseColor("#07C160") else Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { pick(c) }
            }, LinearLayout.LayoutParams(-2, dp(46)))
        }
    }

    // ---------- 輸入邏輯 ----------
    private fun onChar(c: Char) {
        when (mode) {
            0 -> { comp.append(c); refresh() }
            1 -> {
                commit((if (shift) c.uppercaseChar() else c).toString())
                if (shift) { shift = false; render() }
            }
            else -> commit(c.toString())
        }
    }

    private fun refresh() {
        ic?.setComposingText(comp.toString(), 1)
        cands = Dict.lookup(comp.toString())
        showCands()
    }

    private fun pick(c: Pair<String, Int>) {
        ic?.commitText(c.first, 1)
        comp.delete(0, minOf(c.second, comp.length))
        refresh()
    }

    private fun commit(s: String) { ic?.commitText(s, 1) }

    /** 把正在輸入的拼音全部送出（有候選字就選第一個，沒有就送原字母）。 */
    private fun commitAll() {
        while (comp.isNotEmpty()) {
            if (cands.isNotEmpty()) pick(cands[0])
            else {
                ic?.commitText(comp.toString(), 1)
                comp.clear()
                cands = emptyList()
                showCands()
            }
        }
    }

    private fun onSpace() {
        if (mode == 0 && comp.isNotEmpty()) {
            if (cands.isNotEmpty()) pick(cands[0]) else commitAll()
        } else commit(" ")
    }

    private fun onPunc(s: String) { commitAll(); commit(s) }

    private fun onBackspace() {
        if (comp.isNotEmpty()) {
            comp.deleteCharAt(comp.length - 1)
            refresh()
        } else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun onEnter() {
        if (comp.isNotEmpty()) {
            ic?.commitText(comp.toString(), 1)
            comp.clear(); cands = emptyList(); showCands()
            return
        }
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (ei != null && (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 &&
            action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic?.performEditorAction(action)
        } else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun moveCursor(dx: Int, dy: Int) {
        val code = when {
            dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
            dx < 0 -> KeyEvent.KEYCODE_DPAD_LEFT
            dy > 0 -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> KeyEvent.KEYCODE_DPAD_UP
        }
        sendDownUpKeyEvents(code)
    }

    private fun startScan() {
        commitAll()
        startActivity(Intent(this, ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startVoice() {
        commitAll()
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
