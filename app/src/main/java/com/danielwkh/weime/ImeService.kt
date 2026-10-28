package com.danielwkh.weime

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.sqrt

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
    var onAnyTouch: (() -> Unit)? = null
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) onAnyTouch?.invoke()
        if (longPressed && ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                suppress = false
                downX = ev.x; downY = ev.y
                lastX = ev.x; lastY = ev.y
                longPressed = false
                removeCallbacks(longRun)
                postDelayed(longRun, 350)
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
    private var scheme = 0          // 中文輸入法：0 拼音, 1 速成, 2 倉頡
    private var cjWarned = false
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
    private val s2t: android.icu.text.Transliterator? by lazy {
        try { android.icu.text.Transliterator.getInstance("Hans-Hant") }
        catch (_: Throwable) {
            try { android.icu.text.Transliterator.getInstance("Simplified-Traditional") } catch (_: Throwable) { null }
        }
    }
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
        prefs.edit().putInt("mode", mode).putInt("last", lastLetterMode).putInt("scheme", scheme).putBoolean("shift", shift).apply()
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
        scheme = prefs.getInt("scheme", 0)
        if (scheme !in 0..3) scheme = 0
        Thread { CjEngine.load(this) }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Exception) {}
        stopVoice(true)
        Thread { Asr.release() }.start()
        camProvider?.unbindAll()
        registry.currentState = Lifecycle.State.DESTROYED
        camExec.shutdown()
        scanner.close()
        ocr.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        kbRoot = KeyboardRoot(
            this, dp(9), dp(30),
            onStart = { cursorStart() },
            onMove = { dx, dy -> moveCursor(dx, dy) },
            onEnd = { keyArea.alpha = 1f }
        ).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
            onAnyTouch = { stopDelRepeat() }
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
        }
        midFrame = FrameLayout(this)

        // 工具欄：剪貼板、簡繁、表情、語音、設定
        toolbar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#202124"))
        }
        fun tool(label: String, size: Float, click: () -> Unit): TextView {
            val t = cell(label, size, "#303134", Color.LTGRAY, click).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#303134"))
                }
            }
            tools.addView(t, LinearLayout.LayoutParams(dp(38), dp(38)).apply { setMargins(dp(5), 0, dp(5), 0) })
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
        clipChip.addView(TextView(this).apply { text = "📋"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) })
        clipChipText = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
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
            setBackgroundColor(Color.parseColor("#202124"))
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
            setBackgroundColor(Color.parseColor("#202124"))
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
        stopDelRepeat()
        stopScan()
        stopVoice(true)
        closePanel()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        stopDelRepeat()
        stopScan()
        stopVoice(true)
        closePanel()
    }

    // ---------- UI ----------
    private fun cell(t: CharSequence, size: Float, bg: String, fg: Int = Color.BLACK, click: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            val darkBg = when (bg.uppercase()) {
                "#FFFFFF" -> "#4A4A4A"
                "#D0D0D0", "#EDEDED", "#FFF3C4" -> "#343638"
                else -> bg
            }
            setTextColor(if (fg == Color.BLACK) Color.WHITE else if (fg == Color.GRAY) Color.LTGRAY else fg)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.parseColor(darkBg))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener {
                click()
                saveState()
            }
        }

    private var delHeld = false
    private var delView: View? = null

    private fun stopDelRepeat() {
        delHeld = false
        handler.removeCallbacks(repeatDel)
        delView?.alpha = 1f
        delView = null
    }

    private val repeatDel = object : Runnable {
        override fun run() {
            if (!delHeld) return
            val v = delView
            // 手指已不在刪除鍵上、或鍵盤已換畫面／隱藏，就立刻停止
            if (v == null || !v.isAttachedToWindow || !v.isShown) { stopDelRepeat(); return }
            if (!deleteStep()) { stopDelRepeat(); return }
            handler.postDelayed(this, 60)
        }
    }

    /** 連續刪除的一步：先確認編輯框真的還有東西可刪，並同步等它處理完，避免事件堆積、放手後還繼續刪。 */
    private fun deleteStep(): Boolean {
        if (comp.isNotEmpty()) {
            comp.deleteCharAt(comp.length - 1)
            refresh()
            return true
        }
        val c = ic ?: return false
        if (!c.getSelectedText(0).isNullOrEmpty()) {
            c.commitText("", 1)
            return true
        }
        val before = c.getTextBeforeCursor(1, 0) ?: run {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return true
        }
        if (before.isEmpty()) return false
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        c.getTextBeforeCursor(1, 0)
        return true
    }

    /** 刪除鍵：按住會連續刪除；放手、手指移開、再按任何鍵都會停止。 */
    private fun delKey(): TextView = cell("⌫", 18f, "#D0D0D0") {}.apply {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (delView === v) stopDelRepeat()
            }
        })
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    kbRoot.suppress = true
                    stopDelRepeat()
                    delHeld = true
                    delView = v
                    v.alpha = 0.6f
                    onBackspace()
                    handler.postDelayed(repeatDel, 400)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height) stopDelRepeat()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDelRepeat()
            }
            true
        }
    }

    private fun add(row: LinearLayout, v: View, w: Float, h: Int = dp(50)) {
        row.addView(v, LinearLayout.LayoutParams(0, h, w).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })
    }

    private fun alphaLabel(ch: Char, row: Int): CharSequence {
        val top = when (row) {
            0 -> "1234567890".getOrNull("qwertyuiop".indexOf(ch.lowercaseChar()))?.toString().orEmpty()
            1 -> "-/:;()\$&@".getOrNull("asdfghjkl".indexOf(ch.lowercaseChar()))?.toString().orEmpty()
            else -> ""
        }
        if (top.isEmpty()) return if (shift && mode == 1) ch.uppercaseChar().toString() else ch.toString()
        val s = android.text.SpannableString("$top\n${if (shift && mode == 1) ch.uppercaseChar() else ch}")
        s.setSpan(android.text.style.RelativeSizeSpan(0.55f), 0, top.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(android.text.style.ForegroundColorSpan(Color.LTGRAY), 0, top.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    /** 英文鍵帽將小副標固定在右上角，主字母獨立置中，避免多行文字把字母擠到下緣。 */
    private fun alphaKey(ch: Char, row: Int): View {
        val secondary = when (row) {
            0 -> "1234567890".getOrNull("qwertyuiop".indexOf(ch.lowercaseChar()))?.toString().orEmpty()
            1 -> "-/:;()\$&@".getOrNull("asdfghjkl".indexOf(ch.lowercaseChar()))?.toString().orEmpty()
            else -> ""
        }
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4A4A4A"))
                cornerRadius = dp(6).toFloat()
            }
            addView(TextView(this@ImeService).apply {
                text = if (shift) ch.uppercaseChar() else ch
                textSize = 21f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                translationY = -dp(2).toFloat()
            }, FrameLayout.LayoutParams(-1, -1))
            if (secondary.isNotEmpty()) addView(TextView(this@ImeService).apply {
                text = secondary
                textSize = 9f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(3), dp(6), 0)
            }, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener {
                onChar(ch)
                saveState()
            }
        }
    }

    /** 短按輸入空格；長按直接打開離線語音並開始錄音。 */
    private fun spaceKey(label: String, shortAction: () -> Unit = { onSpace() }): View {
        val v = cell(label, 16f, "#4A4A4A", Color.WHITE, shortAction)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) kbRoot.suppress = true
            false
        }
        v.setOnLongClickListener {
            kbRoot.suppress = true
            startVoice()
            handler.postDelayed({
                if (::extraPanel.isInitialized && extraPanel.visibility == View.VISIBLE && voiceBtn != null && !recording && !voiceBusy) {
                    startRecording()
                }
            }, 120)
            true
        }
        return v
    }

    private fun render() {
        keyArea.removeAllViews()
        if (mode == 3) {
            keyArea.addView(numPage())
            return
        }
        val strokeMode = mode == 0 && scheme == 3
        if (strokeMode) {
            keyArea.addView(strokePage())
            keyArea.addView(strokeBottomRow())
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
                val cjKeys = mode == 0 && scheme != 0
                val key = when {
                    cjKeys -> cell(cjLabel(ch), 16f, "#FFFFFF") { onChar(ch) }
                    mode == 1 && i < 2 -> alphaKey(ch, i)
                    else -> {
                        val label = if (mode != 2) alphaLabel(ch, i) else ch.toString()
                        cell(label, 20f, "#FFFFFF") { onChar(ch) }
                    }
                }
                add(line, key, 1f)
            }
            if (i == 1 && mode != 2) add(line, View(this), 0.5f)
            if (i == 2) add(line, delKey(), 1.5f)   // 刪除鍵固定在第三行最右邊
            keyArea.addView(line)
        }

        val b = LinearLayout(this)
        if (mode == 1) {
            add(b, cell("123", 14f, "#D0D0D0") {
                lastLetterMode = mode; mode = 3; render()
            }, 1.15f)
            add(b, cell("😀", 18f, "#D0D0D0") { openEmojiPanel(1) }, 0.8f)
            add(b, cell(",", 20f, "#FFFFFF") { commit(",") }, 0.7f)
            add(b, spaceKey("🎙", { commit(" ") }), 3.5f)
            add(b, cell("英", 15f, "#D0D0D0") { mode = 0; scheme = enabledSchemes()[0]; shift = false; render() }, 1f)
            add(b, cell("換行", 15f, "#07C160", Color.WHITE) { onEnter() }, 1.35f)
        } else if (mode == 2) {
            add(b, cell("ABC", 15f, "#D0D0D0") { mode = lastLetterMode; render(); showCands() }, 1.5f)
        } else {
            add(b, cell("符", 15f, "#D0D0D0") {
                commitAll()
                lastLetterMode = mode
                mode = 2
                shift = false
                render()
            }, 1.2f)
            add(b, cell(if (mode == 0) arrayOf("拼", "速", "倉", "筆")[scheme] else "EN", 15f, "#D0D0D0") {
                cycleLang()
            }, 1.2f)
        }
        val comma = if (zh) "，" else ","
        val period = if (zh) "。" else "."
        if (mode != 1) {
            add(b, cell(comma, 20f, "#FFFFFF") { onPunc(comma) }, 1f)
            add(b, spaceKey("空格"), 3f)
            add(b, cell(period, 20f, "#FFFFFF") { onPunc(period) }, 1f)
            add(b, cell("123", 15f, "#D0D0D0") {
                commitAll()
                if (mode < 2) lastLetterMode = mode
                mode = 3
                shift = false
                render()
            }, 1.2f)
            add(b, cell("⏎", 18f, "#07C160", Color.WHITE) { onEnter() }, 1.5f)
        }
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
                setTextColor(if (i == 0) Color.parseColor("#07C160") else Color.WHITE)
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
            0 -> {
                // 速成滿 2 碼、倉頡滿 5 碼：先送出第一候選，再開始下一個字
                if (scheme != 0 && comp.length >= CjEngine.maxLen(scheme)) commitAll()
                comp.append(c)
                refresh()
            }
            1 -> {
                commit((if (shift) c.uppercaseChar() else c).toString())
                if (shift) { shift = false; render() }
            }
            else -> commit(c.toString())
        }
    }

    private fun cjActive() = mode == 0 && scheme != 0

    /** 編輯框裡顯示的組字內容：拼音顯示字母，速成／倉頡顯示字根。 */
    private fun compDisplay(): String = if (cjActive()) CjEngine.display(scheme, comp) else comp.toString()

    private fun refresh() {
        ic?.setComposingText(compDisplay(), 1)
        var missing = false
        cands = if (cjActive()) {
            val r = CjEngine.lookup(this, scheme, comp.toString())
            missing = r.isEmpty() && comp.isNotEmpty() && !CjEngine.isReady(scheme)
            r
        } else Dict.lookup(comp.toString())
        showCands()
        if (missing && !cjWarned) {
            cjWarned = true
            notify("尚無此輸入法的碼表（請安裝由 GitHub Actions 編譯的版本）")
        }
    }

    /** 設定裡勾選的中文輸入法（0 拼音、1 速成、2 倉頡、3 筆劃）；一個都沒勾就用拼音。 */
    private fun enabledSchemes(): List<Int> {
        val bits = prefs.getInt("schemes2", prefs.getInt("schemes", 7) or 8)
        val l = (0..3).filter { bits and (1 shl it) != 0 }
        return if (l.isEmpty()) listOf(0) else l
    }

    /** 「中」鍵：依序切換已勾選的中文輸入法，最後一個之後切到英文，再按回到第一個。 */
    private fun cycleLang() {
        commitAll()
        val en = enabledSchemes()
        if (mode == 0) {
            val idx = en.indexOf(scheme)
            if (idx in 0 until en.size - 1) scheme = en[idx + 1] else mode = 1
        } else {
            mode = 0
            scheme = en[0]
        }
        shift = false
        render()
    }

    /** 筆劃鍵盤：橫、豎、撇、點、折五個筆劃鍵 + 清除 + 常用標點；刪除鍵在右邊。 */
    private fun strokePage(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun sideKey(col: LinearLayout, label: String, action: () -> Unit) {
            col.addView(cell(label, 17f, "#343638", Color.WHITE, action),
                LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf("7", "?", "+", "\\").forEach { s -> sideKey(left, s) { onPunc(s) } }

        val center = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun strokeRow(labels: List<Pair<String, () -> Unit>>) {
            val row = LinearLayout(this@ImeService)
            for ((label, action) in labels) {
                row.addView(cell(label, if (label.length > 2) 18f else 25f, "#4A4A4A", Color.WHITE, action),
                    LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
            center.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        strokeRow(listOf("一" to { onChar('h') }, "丨" to { onChar('s') }, "丿" to { onChar('p') }))
        strokeRow(listOf("丶" to { onChar('n') }, "乙" to { onChar('z') }, "＊" to { onPunc("＊") }))
        strokeRow(listOf("@#" to { commitAll(); mode = 2; render() }, "，" to { onPunc("，") }, "ABC" to { commitAll(); lastLetterMode = 1; mode = 1; shift = false; render() }))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(delKey(), LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        })
        sideKey(right, "重輸") { clearComp() }
        sideKey(right, "😀") { openEmojiPanel(1) }
        wrap.addView(left, LinearLayout.LayoutParams(0, dp(162), 0.75f))
        wrap.addView(center, LinearLayout.LayoutParams(0, dp(162), 3.5f))
        wrap.addView(right, LinearLayout.LayoutParams(0, dp(162), 0.85f))
        return wrap
    }

    private fun strokeBottomRow(): View {
        val row = LinearLayout(this)
        add(row, cell("符號", 14f, "#343638") { commitAll(); mode = 2; render() }, 1.15f)
        add(row, cell("123", 14f, "#343638") { commitAll(); lastLetterMode = 0; mode = 3; render() }, 1f)
        add(row, spaceKey("🎙", { onSpace() }), 3.4f)
        add(row, cell("筆", 16f, "#343638") { cycleLang() }, 1f)
        add(row, cell("換行", 14f, "#07C160", Color.WHITE) { onEnter() }, 1.2f)
        return row
    }

    private fun clearComp() {
        comp.setLength(0)
        cands = emptyList()
        ic?.setComposingText("", 1)
        showCands()
    }

    /** 倉頡／速成的按鍵：上面字根、下面小字母。 */
    private fun cjLabel(ch: Char): CharSequence {
        val s = android.text.SpannableString("${CjEngine.radical(ch)}\n${ch.uppercaseChar()}")
        val center = android.text.Layout.Alignment.ALIGN_CENTER
        s.setSpan(android.text.style.AlignmentSpan.Standard(center), 0, 2, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        s.setSpan(android.text.style.AlignmentSpan.Standard(center), 2, 3, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        s.setSpan(android.text.style.RelativeSizeSpan(0.55f), 2, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(android.text.style.ForegroundColorSpan(Color.GRAY), 2, 3, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
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
                ic?.commitText(compDisplay(), 1)
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
            ic?.commitText(compDisplay(), 1)
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

    // ---- 長按後不放手、拖動即可即時移動游標 ----
    private var curPos = -1          // 目前游標位置（-1＝這個編輯框讀不到，改用方向鍵）
    private var curMax = 0
    private var curVerified = false

    private fun cursorStart() {
        commitAll()
        keyArea.alpha = 0.35f
        curVerified = false
        val c = ic
        if (c == null) {
            curPos = -1
            return
        }
        val b = c.getTextBeforeCursor(100000, 0)
        val a = c.getTextAfterCursor(100000, 0)
        if (b != null && a != null) {
            curPos = b.length
            curMax = b.length + a.length
        } else {
            curPos = -1
        }
    }

    private fun moveCursor(dx: Int, dy: Int) {
        val c = ic ?: return
        if (dx != 0) {
            val key = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            if (curPos < 0) {
                sendDownUpKeyEvents(key)
                return
            }
            val np = (curPos + dx).coerceIn(0, curMax)
            if (np == curPos) return
            curPos = np
            c.setSelection(np, np)
            if (!curVerified) {
                curVerified = true
                val real = c.getTextBeforeCursor(100000, 0)?.length
                if (real != np) {
                    // 這個編輯框不接受直接設定游標位置，改用方向鍵
                    curPos = -1
                    sendDownUpKeyEvents(key)
                }
            }
            return
        }
        sendDownUpKeyEvents(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)
        if (curPos >= 0) c.getTextBeforeCursor(100000, 0)?.let { curPos = it.length }
    }

    // ---------- 離線語音輸入（sherpa-onnx + SenseVoice） ----------
    @Volatile private var recording = false
    @Volatile private var discardVoice = false
    @Volatile private var voiceBusy = false
    private var voiceStatus: TextView? = null
    private var voiceBtn: TextView? = null
    private val asrRelease = Runnable { Thread { Asr.release() }.start() }

    private fun asrLang(): String = prefs.getString("asrLang", "") ?: ""

    private fun startVoice() {
        commitAll()
        if (!Asr.installed(this)) {
            notify("尚未下載離線語音模型，請按 ⚙ 設定 → 離線語音輸入下載")
            return
        }
        openVoicePanel()
    }

    private fun stopVoice(discard: Boolean) {
        discardVoice = discard
        recording = false
    }

    private fun setVoice(msg: String) { voiceStatus?.text = msg }

    private fun styleVoiceBtn(rec: Boolean) {
        voiceBtn?.apply {
            text = if (rec) "⏹" else "🎤"
            scaleX = 1f
            scaleY = 1f
            (background as? GradientDrawable)?.setColor(Color.parseColor(if (rec) "#E53935" else "#07C160"))
        }
    }

    private fun openVoicePanel() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val langs = listOf("" to "自動", "yue" to "粵語", "zh" to "普通話", "en" to "English")
        for ((code, label) in langs) {
            val sel = asrLang() == code
            head.addView(TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (sel) Color.WHITE else Color.BLACK)
                background = roundBg(if (sel) "#07C160" else "#EDEDED")
                setOnClickListener {
                    if (!recording && !voiceBusy) {
                        prefs.edit().putString("asrLang", code).apply()
                        openVoicePanel()
                    }
                }
            }, lp(0, dp(34), 1f, dp(3)))
        }
        head.addView(cell("返回", 13f, "#D0D0D0") { closePanel(); updateTop() }, lp(dp(54), dp(34), 0f, dp(3)))
        root.addView(head, lp(-1, dp(44)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        voiceBtn = TextView(this).apply {
            text = "🎤"
            textSize = 34f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#07C160"))
            }
            setOnClickListener { toggleRecording() }
        }
        voiceStatus = TextView(this).apply {
            text = "按一下開始說話"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        }
        body.addView(voiceBtn, lp(dp(84), dp(84)).apply { topMargin = dp(16) })
        body.addView(voiceStatus, lp(-1, -2).apply { topMargin = dp(10) })
        root.addView(body, lp(-1, 0, 1f))
        openPanel(root)
    }

    private fun toggleRecording() {
        if (voiceBusy) return
        if (recording) recording = false else startRecording()
    }

    private fun startRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setVoice("請先授權麥克風，授權後再按一次")
            startActivity(
                Intent(this, PermActivity::class.java).putExtra("perm", "mic").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val sr = Asr.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 2)
            )
        } catch (_: Exception) { null }
        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            ar?.release()
            setVoice("無法使用麥克風（可能被其他 App 佔用）")
            return
        }
        handler.removeCallbacks(asrRelease)
        discardVoice = false
        recording = true
        styleVoiceBtn(true)
        setVoice("說話中…說完再按一下（停頓 2 秒也會自動結束）")
        Thread {
            val maxN = sr * 60
            val samples = FloatArray(maxN)
            var n = 0
            val buf = ShortArray(sr / 10)          // 每塊 0.1 秒
            var noise = 0.0
            var chunks = 0
            var speech = false
            var silent = 0
            var idle = 0
            try {
                ar.startRecording()
                while (recording && n < maxN) {
                    val r = ar.read(buf, 0, buf.size)
                    if (r <= 0) continue
                    var sum = 0.0
                    val take = minOf(r, maxN - n)
                    for (i in 0 until r) {
                        val v = buf[i].toInt()
                        sum += v.toDouble() * v
                        if (i < take) samples[n + i] = v / 32768f
                    }
                    n += take
                    val rms = sqrt(sum / r)
                    chunks++
                    if (chunks <= 3) {
                        noise = maxOf(noise, rms)          // 開頭 0.3 秒當環境噪音
                    } else {
                        val thr = maxOf(minOf(noise * 2.5, 1500.0), 400.0)
                        if (rms > thr) { speech = true; silent = 0; idle = 0 }
                        else if (speech) { silent++; if (silent >= 20) recording = false }   // 說話後停頓 2 秒
                        else { idle++; if (idle >= 100) recording = false }                  // 10 秒沒說話
                    }
                    val lvl = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                    handler.post { voiceBtn?.let { it.scaleX = 1f + lvl * 0.25f; it.scaleY = it.scaleX } }
                }
            } catch (_: Exception) {
            } finally {
                try { ar.stop() } catch (_: Exception) {}
                ar.release()
            }
            recording = false
            val data = samples.copyOf(n)
            val heard = speech
            handler.post { onRecorded(data, heard) }
        }.start()
    }

    private fun onRecorded(data: FloatArray, heard: Boolean) {
        styleVoiceBtn(false)
        if (discardVoice) return
        if (!heard || data.size < Asr.SAMPLE_RATE / 3) {
            setVoice("沒聽到聲音，請再按一下重試")
            return
        }
        voiceBusy = true
        setVoice("辨識中…（第一次載入模型約需數秒）")
        val lang = asrLang()
        Thread {
            val text = try {
                Asr.transcribe(this, data, lang)
            } catch (e: Throwable) {
                handler.post {
                    voiceBusy = false
                    setVoice("辨識失敗：${e.message ?: e.javaClass.simpleName}")
                }
                return@Thread
            }
            handler.post {
                voiceBusy = false
                if (!discardVoice) commitVoice(text)
                scheduleAsrRelease()
            }
        }.start()
    }

    private fun commitVoice(text: String) {
        if (text.isBlank()) { setVoice("沒有辨識到內容，請再試一次"); return }
        // SenseVoice 對普通話多半輸出簡體：依「繁／简」設定統一字形
        val out = try {
            if (simplified) t2s?.transliterate(text) ?: text else s2t?.transliterate(text) ?: text
        } catch (_: Exception) { text }
        commitAll()
        ic?.commitText(out, 1)
        setVoice("已輸入：$out\n（再按一下可繼續說）")
    }

    /** 閒置 3 分鐘後釋放模型記憶體，下次說話時再載入。 */
    private fun scheduleAsrRelease() {
        handler.removeCallbacks(asrRelease)
        handler.postDelayed(asrRelease, 180000)
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
        stopVoice(true)
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
        stopVoice(true)
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
                    // 條碼掃不到：自動改為辨識「條碼正下方」印的數字
                    lastOcr = now
                    setHint("掃不到條碼，正在辨識條碼下方的數字…")
                    val bmp = try {
                        val raw = proxy.toBitmap()
                        val deg = proxy.imageInfo.rotationDegrees
                        if (deg == 0) raw
                        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                    } catch (e: Exception) {
                        null
                    }
                    if (bmp == null) {
                        proxy.close()
                    } else {
                        ocr.process(InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { t -> handleOcr(t, bmp) }
                            .addOnCompleteListener { proxy.close() }
                    }
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

    /**
     * 只接受「正上方有條碼」的數字行：
     * 1) 找出整行幾乎都是數字的文字行（同一水平帶的數字會合併，EAN-13 常被拆成 3 段）；
     * 2) 檢查該行正上方是不是一排垂直條紋（條碼）；
     * 3) 8–14 位，校驗碼正確連續 2 次，或同樣內容連續 3 次才接受。
     */
    private fun handleOcr(t: Text, bmp: Bitmap) {
        if (scanDone || !scanning) return
        class DL(val text: String, val r: Rect)

        val lines = ArrayList<DL>()
        for (b in t.textBlocks) for (l in b.lines) {
            val r = l.boundingBox ?: continue
            val digits = l.text.filter { it.isDigit() }
            val junk = l.text.count { !it.isDigit() && !it.isWhitespace() }
            if (digits.isNotEmpty() && junk <= 1) lines.add(DL(digits, r))
        }
        if (lines.isEmpty()) return

        val cands = ArrayList<DL>(lines)
        // 合併同一水平帶、由左到右的數字段
        val sorted = lines.sortedBy { it.r.centerY() }
        val used = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (used[i]) continue
            val band = ArrayList<DL>()
            band.add(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (!used[j] && abs(sorted[j].r.centerY() - sorted[i].r.centerY()) <= sorted[i].r.height() * 0.6) {
                    band.add(sorted[j]); used[j] = true
                }
            }
            if (band.size > 1) {
                val ordered = band.sortedBy { it.r.left }
                val u = Rect(ordered[0].r)
                for (d in ordered) u.union(d.r)
                cands.add(DL(ordered.joinToString("") { it.text }, u))
            }
        }

        for (c in cands) {
            if (c.text.length !in 8..14) continue
            if (!barcodeAbove(bmp, c.r)) continue
            val ok = gs1Ok(c.text)
            val n = (ocrSeen[c.text] ?: 0) + 1
            ocrSeen[c.text] = n
            if ((ok && n >= 2) || n >= 3) {
                accept(c.text)
                return
            }
        }
    }

    /** 數字行正上方是不是條碼：該區域「左右方向的明暗變化」遠大於「上下方向」，且對比夠強。 */
    private fun barcodeAbove(bmp: Bitmap, r: Rect): Boolean {
        val lh = maxOf(r.height(), 8)
        val x0 = (r.left + r.width() * 0.08f).toInt().coerceIn(0, bmp.width - 2)
        val x1 = (r.right - r.width() * 0.08f).toInt().coerceIn(x0 + 2, bmp.width)
        val y1 = (r.top - lh * 0.2f).toInt().coerceAtMost(bmp.height)
        val y0 = (r.top - lh * 3.0f).toInt().coerceAtLeast(0)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 16 || h < lh) return false
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, x0, y0, w, h)
        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
        }
        var gx = 0L
        var gy = 0L
        for (y in 0 until h - 1) {
            val row = y * w
            for (x in 0 until w - 1) {
                val i = row + x
                gx += abs(lum[i + 1] - lum[i])
                gy += abs(lum[i + w] - lum[i])
            }
        }
        val n = ((w - 1) * (h - 1)).toDouble()
        val mx = gx / n
        val my = gy / n
        return mx >= 10.0 && mx >= my * 3.0
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
        stopDelRepeat()
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
