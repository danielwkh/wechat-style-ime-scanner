package com.danielwkh.weime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.text.TextUtils
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import org.json.JSONArray
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

class ImeService : InputMethodService(), LifecycleOwner {
    companion object {
        @Volatile var pending: String? = null
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private var mode = 0            // 0 中文拼音, 1 英文, 2 符號, 3 九宮格數字
    private var lastLetterMode = 0
    private var shift = false
    private val comp = StringBuilder()
    private var cands: List<Pair<String, Int>> = emptyList()
    private lateinit var kbRoot: KeyboardRoot
    private lateinit var candBar: LinearLayout
    private lateinit var keyArea: LinearLayout
    private lateinit var scanPanel: FrameLayout
    private lateinit var pv: PreviewView
    private lateinit var scanBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("kb", Context.MODE_PRIVATE) }

    // 掃描相關
    private val scanner = BarcodeScanning.getClient()
    private val camExec = Executors.newSingleThreadExecutor()
    private var camProvider: ProcessCameraProvider? = null
    @Volatile private var scanning = false
    @Volatile private var scanDone = false
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrSeen = HashMap<String, Int>()
    private var scanStart = 0L
    private var lastOcr = 0L
    private var outName = false          // false＝輸出條碼數字，true＝輸出商品名
    private lateinit var outBtn: TextView

    // 頂部工具欄／剪貼板／表情／簡繁
    private lateinit var midFrame: FrameLayout
    private lateinit var toolbar: HorizontalScrollView
    private lateinit var candScroll: HorizontalScrollView
    private lateinit var clipChip: LinearLayout
    private lateinit var clipChipText: TextView
    private lateinit var convBtn: TextView
    private lateinit var extraPanel: FrameLayout
    private var notice = false
    private var simplified = false
    private var convWarned = false
    private var lastKeyH = 0
    private val clips = ArrayList<String>()
    private var clipSuggest: String? = null
    private var clipTime = 0L
    private var clipDismissed: String? = null
    private val cm by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClipChanged() }
    private val t2s: android.icu.text.Transliterator? by lazy {
        try { android.icu.text.Transliterator.getInstance("Hant-Hans") }
        catch (_: Throwable) {
            try { android.icu.text.Transliterator.getInstance("Traditional-Simplified") } catch (_: Throwable) { null }
        }
    }

    private val ic: InputConnection? get() = currentInputConnection
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 每次按鍵都記錄目前使用的鍵盤（中／英／符號／數字、Shift），下次顯示時還原。 */
    private fun saveState() {
        prefs.edit().putInt("mode", mode).putInt("last", lastLetterMode).putBoolean("shift", shift).apply()
    }

    override fun onCreate() {
        super.onCreate()
        registry.currentState = Lifecycle.State.CREATED
        mode = prefs.getInt("mode", 0)
        lastLetterMode = prefs.getInt("last", 0)
        shift = prefs.getBoolean("shift", false)
        outName = prefs.getBoolean("outName", false)
        simplified = prefs.getBoolean("simp", false)
        loadClips()
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        if (mode !in 0..3) mode = 0
        if (lastLetterMode !in 0..1) lastLetterMode = 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        camProvider?.unbindAll()
        registry.currentState = Lifecycle.State.DESTROYED
        camExec.shutdown()
        scanner.close()
        ocr.close()
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
        midFrame = FrameLayout(this)

        // 工具欄：剪貼板、簡繁、表情、語音、設定
        toolbar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun tool(label: String, size: Float, click: () -> Unit): TextView {
            val t = cell(label, size, "#EDEDED", click = click)
            tools.addView(t, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(3), 0, dp(2), 0) })
            return t
        }
        tool("📋", 17f) { openClipPanel() }
        convBtn = tool(if (simplified) "简" else "繁", 17f) { toggleSimplified() }
        tool("😀", 17f) { openEmojiPanel(1) }
        tool("🎤", 17f) { startVoice() }
        tool("⚙", 17f) { openSettings() }
        toolbar.addView(tools)

        // 候選字列
        candScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        candBar = LinearLayout(this)
        candScroll.addView(candBar)

        // 剛複製的文字：顯示在工具欄位置，點文字貼上，∨ 開剪貼板，✕ 關閉
        clipChip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(10), 0, 0, 0)
        }
        clipChip.addView(TextView(this).apply { text = "📋"; textSize = 17f; gravity = Gravity.CENTER })
        clipChipText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { useSuggestedClip() }
        }
        clipChip.addView(clipChipText, LinearLayout.LayoutParams(0, -1, 1f))
        clipChip.addView(TextView(this).apply {
            text = "∨"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.GRAY)
            setOnClickListener { openClipPanel() }
        }, LinearLayout.LayoutParams(dp(38), -1))
        clipChip.addView(TextView(this).apply {
            text = "✕"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(Color.GRAY)
            setOnClickListener { dismissClip() }
        }, LinearLayout.LayoutParams(dp(38), -1))

        midFrame.addView(toolbar, FrameLayout.LayoutParams(-1, -1))
        midFrame.addView(candScroll, FrameLayout.LayoutParams(-1, -1))
        midFrame.addView(clipChip, FrameLayout.LayoutParams(-1, -1))
        top.addView(midFrame, LinearLayout.LayoutParams(0, dp(46), 1f))

        outBtn = cell(if (outName) "輸出名" else "輸出碼", 13f, "#FFF3C4") {
            outName = !outName
            prefs.edit().putBoolean("outName", outName).apply()
            outBtn.text = if (outName) "輸出名" else "輸出碼"
        }
        top.addView(outBtn, LinearLayout.LayoutParams(dp(58), dp(38)).apply { setMargins(dp(4), 0, 0, 0) })
        scanBtn = cell("掃描", 15f, "#07C160", Color.WHITE) { toggleScan() }
        top.addView(scanBtn, LinearLayout.LayoutParams(dp(64), dp(38)).apply { setMargins(dp(4), 0, dp(6), 0) })

        keyArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(6))
        }

        // 掃描面板：和鍵盤同樣大小，直接在鍵盤範圍內顯示相機畫面
        pv = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        scanPanel = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        scanPanel.addView(pv, FrameLayout.LayoutParams(-1, -1))
        scanPanel.addView(TextView(this).apply {
            text = "把條碼對準畫面"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#66000000"))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        kbRoot.addView(top)
        kbRoot.addView(keyArea)
        kbRoot.addView(scanPanel, LinearLayout.LayoutParams(-1, dp(228)))
        extraPanel = NoDragFrame(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#EDEDED"))
        }
        kbRoot.addView(extraPanel, LinearLayout.LayoutParams(-1, dp(228)))
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
            // 語音返回後，自動把同一個鍵盤重新顯示出來
            handler.postDelayed({ requestShowSelf(0) }, 200)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        checkClip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(repeatDel)
        stopScan()
        closePanel()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        handler.removeCallbacks(repeatDel)
        stopScan()
        closePanel()
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
        if (mode == 3) {
            keyArea.addView(numPage())
            return
        }
        val rows = if (mode == 2) listOf("1234567890", "@#\$%&*-+=/", "()「」_~…")
        else listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val zh = if (mode >= 2) lastLetterMode == 0 else mode == 0

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
            add(b, cell("符", 15f, "#D0D0D0") {
                commitAll()
                lastLetterMode = mode
                mode = 2
                shift = false
                render()
            }, 1.2f)
            add(b, cell(if (mode == 0) "中" else "EN", 15f, "#D0D0D0") {
                commitAll()
                mode = 1 - mode
                shift = false
                render()
            }, 1.2f)
        }
        val comma = if (zh) "，" else ","
        val period = if (zh) "。" else "."
        add(b, cell(comma, 20f, "#FFFFFF") { onPunc(comma) }, 1f)
        add(b, cell("空格", 15f, "#FFFFFF") { onSpace() }, 3f)
        add(b, cell(period, 20f, "#FFFFFF") { onPunc(period) }, 1f)
        add(b, cell("123", 15f, "#D0D0D0") {
            commitAll()
            if (mode < 2) lastLetterMode = mode
            mode = 3
            shift = false
            render()
        }, 1.2f)
        add(b, cell("⏎", 18f, "#07C160", Color.WHITE) { onEnter() }, 1.5f)
        keyArea.addView(b)
    }

    /** 九宮格數字鍵盤：左邊常用標點，中間 1-9 + 0，右邊刪除／空格／確定。 */
    private fun numPage(): View {
        val h = dp(54) * 4
        val wrap = LinearLayout(this)

        fun key(parent: LinearLayout, label: String, bg: String, w: Float,
                fg: Int = Color.BLACK, size: Float = 20f, click: () -> Unit) {
            val vertical = parent.orientation == LinearLayout.VERTICAL
            parent.addView(
                cell(label, size, bg, fg, click),
                LinearLayout.LayoutParams(if (vertical) -1 else 0, if (vertical) 0 else -1, w).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
            )
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (s in listOf("，", "。", "？", "！")) key(left, s, "#FFFFFF", 1f) { commit(s) }

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in listOf("123", "456", "789")) {
            val row = LinearLayout(this)
            for (d in r) key(row, d.toString(), "#FFFFFF", 1f, size = 22f) { commit(d.toString()) }
            mid.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val last = LinearLayout(this)
        key(last, "ABC", "#D0D0D0", 1f, size = 15f) { mode = lastLetterMode; render() }
        key(last, "0", "#FFFFFF", 1f, size = 22f) { commit("0") }
        key(last, ".", "#FFFFFF", 1f, size = 22f) { commit(".") }
        mid.addView(last, LinearLayout.LayoutParams(-1, 0, 1f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(delKey(), LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        key(right, "空格", "#FFFFFF", 1f, size = 15f) { commit(" ") }
        key(right, "⏎", "#07C160", 2f, Color.WHITE, 18f) { onEnter() }

        wrap.addView(left, LinearLayout.LayoutParams(0, h, 1f))
        wrap.addView(mid, LinearLayout.LayoutParams(0, h, 3f))
        wrap.addView(right, LinearLayout.LayoutParams(0, h, 1.3f))
        return wrap
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
        updateTop()
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
        ic?.commitText(conv(c.first), 1)
        comp.delete(0, minOf(c.second, comp.length))
        refresh()
    }

    private fun commit(s: String) { ic?.commitText(conv(s), 1) }

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

    private fun startVoice() {
        commitAll()
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- 頂部工具欄 ----------
    /** 面板內長按不要觸發「拖動游標」。 */
    private inner class NoDragFrame(ctx: Context) : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) kbRoot.suppress = true
            return super.dispatchTouchEvent(ev)
        }
    }

    /** 依狀態決定頂部顯示什麼：候選字（輸入中）> 剛複製的文字 > 工具欄。 */
    private fun updateTop() {
        if (!::midFrame.isInitialized) return
        if (clipSuggest != null && SystemClock.elapsedRealtime() - clipTime > 180000) clipSuggest = null
        val showCand = notice || comp.isNotEmpty()
        val showChip = !showCand && clipSuggest != null
        candScroll.visibility = if (showCand) View.VISIBLE else View.GONE
        clipChip.visibility = if (showChip) View.VISIBLE else View.GONE
        toolbar.visibility = if (!showCand && !showChip) View.VISIBLE else View.GONE
        if (showChip) clipChipText.text = clipSuggest!!.replace('\n', ' ')
    }

    private fun keyH(): Int {
        if (::keyArea.isInitialized && keyArea.height > 0) lastKeyH = keyArea.height
        return if (lastKeyH > 0) lastKeyH else dp(228)
    }

    private fun openPanel(content: View) {
        stopScan()
        commitAll()
        val h = keyH()
        extraPanel.removeAllViews()
        extraPanel.layoutParams = LinearLayout.LayoutParams(-1, h)
        extraPanel.addView(content, FrameLayout.LayoutParams(-1, -1))
        keyArea.visibility = View.GONE
        extraPanel.visibility = View.VISIBLE
    }

    private fun closePanel() {
        if (!::extraPanel.isInitialized || extraPanel.visibility != View.VISIBLE) return
        extraPanel.visibility = View.GONE
        extraPanel.removeAllViews()
        if (!scanning) keyArea.visibility = View.VISIBLE
    }

    private fun openSettings() {
        commitAll()
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun roundBg(color: String) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(6).toFloat()
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, m: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(m, m, m, m) }

    // ---------- 簡繁轉換 ----------
    private fun conv(s: String): String {
        if (!simplified) return s
        val t = t2s
        if (t == null) {
            if (!convWarned) { convWarned = true; notify("此手機不支援簡繁轉換") }
            return s
        }
        return try { t.transliterate(s) } catch (_: Exception) { s }
    }

    private fun toggleSimplified() {
        commitAll()
        simplified = !simplified
        prefs.edit().putBoolean("simp", simplified).apply()
        convBtn.text = if (simplified) "简" else "繁"
        if (simplified && t2s == null) conv("繁")   // 不支援時提示一次
    }

    // ---------- 剪貼板 ----------
    private fun loadClips() {
        try {
            val a = JSONArray(prefs.getString("clips", "[]"))
            for (i in 0 until a.length()) clips.add(a.getString(i))
        } catch (_: Exception) {}
    }

    private fun saveClips() { prefs.edit().putString("clips", JSONArray(clips).toString()).apply() }

    private fun addClip(t: String) {
        if (t.isBlank() || t.length > 5000) return
        clips.remove(t)
        clips.add(0, t)
        while (clips.size > 30) clips.removeAt(clips.size - 1)
        saveClips()
    }

    private fun readClip(): String? = try {
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun onClipChanged() {
        val t = readClip() ?: return
        addClip(t)
        clipSuggest = t
        clipTime = SystemClock.elapsedRealtime()
        clipDismissed = null
        updateTop()
    }

    /** 鍵盤打開時：3 分鐘內複製過的文字，顯示在工具欄位置。 */
    private fun checkClip() {
        val t = readClip() ?: run { updateTop(); return }
        if (clips.firstOrNull() != t) addClip(t)
        val ts = try { cm.primaryClipDescription?.timestamp ?: 0L } catch (_: Exception) { 0L }
        val age = System.currentTimeMillis() - ts
        if (ts > 0 && age in 0..180000 && t != clipDismissed) {
            clipSuggest = t
            clipTime = SystemClock.elapsedRealtime() - age
        }
        updateTop()
    }

    private fun useSuggestedClip() {
        val t = clipSuggest ?: return
        commitAll()
        ic?.commitText(t, 1)
        dismissClip()
    }

    private fun dismissClip() {
        clipDismissed = clipSuggest
        clipSuggest = null
        updateTop()
    }

    private fun openClipPanel() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        head.addView(TextView(this).apply {
            text = "剪貼板（點一下貼上）"; textSize = 14f; setTextColor(Color.BLACK)
            setPadding(dp(12), 0, 0, 0)
        }, lp(0, dp(44), 1f))
        head.addView(cell("清空", 13f, "#EDEDED") {
            clips.clear(); saveClips(); clipSuggest = null; openClipPanel()
        }, lp(dp(54), dp(32), 0f, dp(3)))
        head.addView(cell("返回", 13f, "#07C160", Color.WHITE) { closePanel(); updateTop() },
            lp(dp(54), dp(32), 0f, dp(3)))
        root.addView(head, lp(-1, dp(44)))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        if (clips.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "還沒有複製過文字"; textSize = 14f; setTextColor(Color.GRAY)
                gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
            })
        }
        for (t in clips.toList()) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg("#FFFFFF")
            }
            row.addView(TextView(this).apply {
                text = t.replace('\n', ' ')
                textSize = 15f
                setTextColor(Color.BLACK)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(8), dp(8), dp(8))
                setOnClickListener {
                    commitAll()
                    ic?.commitText(t, 1)
                    closePanel()
                    updateTop()
                }
            }, lp(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = "✕"; textSize = 15f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
                setOnClickListener {
                    clips.remove(t); saveClips()
                    if (clipSuggest == t) clipSuggest = null
                    openClipPanel()
                }
            }, lp(dp(44), dp(44)))
            list.addView(row, lp(-1, -2, 0f, dp(2)))
        }
        val sv = ScrollView(this)
        sv.addView(list)
        root.addView(sv, lp(-1, 0, 1f))
        openPanel(root)
    }

    // ---------- 表情符號 ----------
    private val emojiCats: List<Pair<String, String>> = listOf(
        "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 😉 😊 😇 🥰 😍 🤩 😘 😋 😛 😜 🤪 😎 🤓 🥳 😏 😒 😞 😔 😟 😕 🙁 😣 😖 😫 😩 🥺 😢 😭 😤 😠 😡 🤬 🤯 😳 🥵 🥶 😱 😨 😰 😥 😓 🤗 🤔 🤭 🤫 😶 😐 😑 😬 🙄 😯 😴 🤤 😷 🤒 🤕 🤢 🤮 🤧 😵 🤠 🥱 😈 👻 💀 🤡 💩",
        "👍" to "👍 👎 👌 ✌\uFE0F 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝\uFE0F ✋ 🤚 🖐\uFE0F 👋 👏 🙌 👐 🤝 🙏 💪 ✍\uFE0F 🤳 👀 👂 👃 👄 🧠 🦷 👶 👦 👧 👨 👩 👴 👵",
        "❤" to "❤\uFE0F 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣\uFE0F 💕 💞 💓 💗 💖 💘 💝 💯 💢 💥 💫 💦 💨 🔥 ✨ ⭐ 🌟 ⚡ 🎉 🎊 🎁 🏆 ✅ ❌ ❗ ❓ ⭕ 🔴 🟠 🟡 🟢 🔵 🟣",
        "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🐔 🐧 🐦 🐤 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🐛 🦋 🐌 🐞 🐜 🐢 🐍 🐙 🦀 🐠 🐬 🐳 🐘 🌸 🌹 🌻 🌲 🍀 🌈 ☀\uFE0F 🌙 ⭐ ☁\uFE0F ❄\uFE0F",
        "🍎" to "🍎 🍊 🍋 🍌 🍉 🍇 🍓 🍒 🍑 🥭 🍍 🥝 🍅 🥕 🌽 🥔 🍞 🧀 🥚 🍳 🥓 🍔 🍟 🍕 🌭 🥪 🌮 🍜 🍝 🍣 🍤 🍙 🍚 🍰 🎂 🍦 🍩 🍪 🍫 🍬 ☕ 🍵 🍺 🍻 🍷 🥂 🥤",
        "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🎱 🏓 🏸 🥊 🎯 🎮 🎲 🎵 🎶 🎤 🎧 🎬 📷 📱 💻 ⌚ 💡 🔑 🔒 📌 📎 ✂\uFE0F 📦 💰 💳 🛒 🚗 🚕 🚌 🚇 ✈\uFE0F 🚀 🏠 🏢 ⏰ 📅 📞 ✉\uFE0F"
    )
    private val emojiTabs = listOf("🕘") + emojiCats.map { it.first }

    private fun recentEmoji(): List<String> =
        (prefs.getString("emojiRecent", "") ?: "").split(" ").filter { it.isNotEmpty() }

    private fun addRecentEmoji(e: String) {
        val l = ArrayList(recentEmoji())
        l.remove(e)
        l.add(0, e)
        while (l.size > 24) l.removeAt(l.size - 1)
        prefs.edit().putString("emojiRecent", l.joinToString(" ")).apply()
    }

    private fun openEmojiPanel(tab: Int) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        for ((i, label) in emojiTabs.withIndex()) {
            tabs.addView(TextView(this).apply {
                text = label; textSize = 20f; gravity = Gravity.CENTER
                if (i == tab) background = roundBg("#EDEDED")
                setOnClickListener { openEmojiPanel(i) }
            }, lp(0, dp(36), 1f, dp(2)))
        }
        root.addView(tabs, lp(-1, dp(40)))

        val items = if (tab == 0) recentEmoji() else emojiCats[tab - 1].second.split(" ").filter { it.isNotEmpty() }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(2), dp(2), dp(2)) }
        if (items.isEmpty()) {
            grid.addView(TextView(this).apply {
                text = "還沒有最近使用的表情"; textSize = 14f; setTextColor(Color.GRAY)
                gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0)
            })
        }
        val cols = 8
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this)
            for (j in 0 until cols) {
                val e = items.getOrNull(i + j)
                row.addView(TextView(this).apply {
                    if (e != null) {
                        text = e; textSize = 24f; gravity = Gravity.CENTER
                        setOnClickListener {
                            commitAll()
                            ic?.commitText(e, 1)
                            addRecentEmoji(e)
                        }
                    }
                }, lp(0, dp(44), 1f))
            }
            grid.addView(row)
            i += cols
        }
        val sv = ScrollView(this)
        sv.addView(grid)
        root.addView(sv, lp(-1, 0, 1f))

        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.WHITE) }
        bottom.addView(cell("返回", 15f, "#D0D0D0") { closePanel(); updateTop() }, lp(dp(70), dp(40), 0f, dp(3)))
        bottom.addView(View(this), lp(0, dp(40), 1f))
        bottom.addView(delKey(), lp(dp(70), dp(40), 0f, dp(3)))
        root.addView(bottom, lp(-1, dp(46)))
        openPanel(root)
    }

    // ---------- 掃描（在鍵盤範圍內） ----------
    private fun toggleScan() { if (scanning) stopScan() else startScan() }

    private fun startScan() {
        commitAll()
        closePanel()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "請先授權相機", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PermActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val h = keyH()
        scanPanel.layoutParams = LinearLayout.LayoutParams(-1, h)
        keyArea.visibility = View.GONE
        scanPanel.visibility = View.VISIBLE
        scanBtn.text = "關閉"
        scanning = true
        scanDone = false
        ocrSeen.clear()
        scanStart = SystemClock.elapsedRealtime()
        lastOcr = 0L
        setHint("把條碼對準畫面")
        if (outName) Thread { ProductDb.count(this) }.start()
        registry.currentState = Lifecycle.State.STARTED

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!scanning) return@addListener
            val provider = future.get()
            camProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(camExec) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { camProvider?.unbindAll() } catch (_: Exception) {}
        registry.currentState = Lifecycle.State.CREATED
        scanPanel.visibility = View.GONE
        keyArea.visibility = View.VISIBLE
        scanBtn.text = "掃描"
    }

    private fun setHint(t: String) {
        (scanPanel.getChildAt(1) as? TextView)?.text = t
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val img = proxy.image
        if (img == null || scanDone || !scanning) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { list ->
                val v = list.firstNotNullOfOrNull { it.rawValue }
                val now = SystemClock.elapsedRealtime()
                if (v != null) {
                    accept(v)
                    proxy.close()
                } else if (scanning && !scanDone && now - scanStart > 2000 && now - lastOcr > 350) {
                    // 條碼掃不到：自動改為辨識條碼附近印的數字
                    lastOcr = now
                    setHint("掃不到條碼，正在辨識附近的數字…")
                    ocr.process(input)
                        .addOnSuccessListener { t -> handleOcr(t) }
                        .addOnCompleteListener { proxy.close() }
                } else {
                    proxy.close()
                }
            }
            .addOnFailureListener { proxy.close() }
    }

    private fun accept(v: String) {
        if (scanDone || !scanning) return
        scanDone = true
        handler.post { onScanned(v) }
    }

    /** 從辨識到的文字找出 8–14 位的數字：校驗碼正確連續 2 次，或同樣內容連續 4 次才接受。 */
    private fun handleOcr(t: Text) {
        if (scanDone || !scanning) return
        val cands = ArrayList<String>()
        for (b in t.textBlocks) {
            cands.add(b.text)
            for (l in b.lines) cands.add(l.text)
        }
        for (c in cands) {
            val digits = c.filter { it.isDigit() }
            val junk = c.count { !it.isDigit() && !it.isWhitespace() }
            if (digits.length !in 8..14 || junk > 1) continue
            val ok = gs1Ok(digits)
            val n = (ocrSeen[digits] ?: 0) + 1
            ocrSeen[digits] = n
            if ((ok && n >= 2) || n >= 4) {
                accept(digits)
                return
            }
        }
    }

    private fun gs1Ok(d: String): Boolean {
        if (d.length != 8 && d.length != 12 && d.length != 13 && d.length != 14) return false
        var sum = 0
        for (i in 0 until d.length - 1) {
            val v = d[d.length - 2 - i] - '0'
            sum += if (i % 2 == 0) v * 3 else v
        }
        return (10 - sum % 10) % 10 == d.last() - '0'
    }

    /** 在鍵盤頂部的候選列顯示 4 秒提示（比 Toast 可靠，部分手機會擋掉 Toast）。 */
    private fun notify(msg: String) {
        candBar.removeAllViews()
        candBar.addView(TextView(this).apply {
            text = msg
            textSize = 13f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }, LinearLayout.LayoutParams(-2, dp(46)))
        notice = true
        updateTop()
        handler.postDelayed({ notice = false; showCands() }, 4000)
    }

    /** 掃到後：清除輸入框全部內容 → 貼上（數字或商品名）→ 執行輸入動作（Enter／搜尋／送出）。 */
    private fun onScanned(code: String) {
        stopScan()
        handler.removeCallbacks(repeatDel)
        var text = code
        if (outName) {
            val name = ProductDb.lookup(this, code)
            if (name != null) text = name
            else notify(
                if (ProductDb.count(this) == 0) "尚未匯入商品庫，已輸出數字"
                else "商品庫(${ProductDb.count(this)}筆)找不到 $code，已輸出數字"
            )
        }
        if (!replaceAll(text)) return
        // 等輸入框把新內容處理完，再觸發 Enter／搜尋／送出（只觸發一次）
        handler.postDelayed({ onEnter() }, 200)
    }

    /**
     * 把輸入框的全部內容換成 [text]：一次選取全部再取代，不做大量刪除，
     * 避免部分 App（WebView、自訂輸入框）因 deleteSurroundingText 數量過大而反覆刪除內容。
     */
    private fun replaceAll(text: String): Boolean {
        val c = ic ?: return false
        c.beginBatchEdit()
        try {
            c.finishComposingText()
            var selected = false
            // 方法 1：用 ExtractedText 取得全文長度，精準選取 0..長度
            val et = try { c.getExtractedText(ExtractedTextRequest(), 0) } catch (_: Exception) { null }
            if (et?.text != null) {
                val len = et.startOffset + et.text.length
                selected = c.setSelection(0, len)
            }
            // 方法 2：用「全選」選單動作
            if (!selected) selected = c.performContextMenuAction(android.R.id.selectAll)
            if (selected) {
                c.commitText(text, 1)          // 取代選取範圍
            } else {
                // 方法 3：最後備援，用實際讀到的長度一次刪除
                val before = c.getTextBeforeCursor(100000, 0)?.length ?: 0
                val after = c.getTextAfterCursor(100000, 0)?.length ?: 0
                c.deleteSurroundingText(before, after)
                c.commitText(text, 1)
            }
        } finally {
            c.endBatchEdit()
        }
        return true
    }
}
