package com.danielwkh.weime

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
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
        if (mode !in 0..3) mode = 0
        if (lastLetterMode !in 0..1) lastLetterMode = 0
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        candBar = LinearLayout(this)
        scroll.addView(candBar)
        top.addView(scroll, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(
            cell("🎤", 18f, "#EDEDED") { startVoice() },
            LinearLayout.LayoutParams(dp(48), dp(38)).apply { setMargins(dp(4), 0, 0, 0) }
        )
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

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        handler.removeCallbacks(repeatDel)
        stopScan()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        handler.removeCallbacks(repeatDel)
        stopScan()
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

    private fun startVoice() {
        commitAll()
        startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- 掃描（在鍵盤範圍內） ----------
    private fun toggleScan() { if (scanning) stopScan() else startScan() }

    private fun startScan() {
        commitAll()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "請先授權相機", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PermActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val h = keyArea.height.takeIf { it > 0 } ?: dp(228)
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

    /** 掃到後：清除輸入框全部內容 → 貼上（數字或商品名）→ 執行輸入動作（Enter／搜尋／送出）。 */
    private fun onScanned(code: String) {
        stopScan()
        handler.removeCallbacks(repeatDel)
        var text = code
        if (outName) {
            val name = ProductDb.lookup(this, code)
            if (name != null) text = name
            else Toast.makeText(
                this,
                if (ProductDb.count(this) == 0) "尚未匯入商品庫，已輸出數字" else "商品庫找不到 $code，已輸出數字",
                Toast.LENGTH_LONG
            ).show()
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
