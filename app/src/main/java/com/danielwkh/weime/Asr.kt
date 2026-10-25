package com.danielwkh.weime

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 離線語音辨識（sherpa-onnx + SenseVoice：粵語／普通話／英文／日文／韓文）。
 * 模型不放在 APK 裡，在「設定」按鈕下載一次（約 166MB，解開後約 237MB），之後完全離線。
 */
object Asr {
    const val MODEL_NAME = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
    const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_NAME.tar.bz2"
    const val SAMPLE_RATE = 16000

    @Volatile var cancel = false

    private fun dir(ctx: Context) = File(ctx.filesDir, "asr")
    private fun modelFile(ctx: Context) = File(dir(ctx), "model.int8.onnx")
    private fun tokensFile(ctx: Context) = File(dir(ctx), "tokens.txt")
    private fun okFile(ctx: Context) = File(dir(ctx), "ok")

    fun installed(ctx: Context): Boolean =
        okFile(ctx).exists() && modelFile(ctx).exists() && tokensFile(ctx).exists()

    fun sizeMb(ctx: Context): Long = (modelFile(ctx).length() + tokensFile(ctx).length()) / 1024 / 1024

    @Synchronized
    fun delete(ctx: Context) {
        release()
        dir(ctx).deleteRecursively()
    }

    private class CountingStream(input: InputStream, val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var n = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { n++; onBytes(n) }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) { n += r; onBytes(n) }
            return r
        }
    }

    /** 下載並直接串流解壓（只取 model.int8.onnx 和 tokens.txt）。在背景執行緒呼叫。 */
    fun download(ctx: Context, onProgress: (Int) -> Unit) {
        cancel = false
        release()
        val d = dir(ctx)
        d.deleteRecursively()
        d.mkdirs()
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("下載失敗（${conn.responseCode}）")
            val total = conn.contentLengthLong
            var lastPct = -1
            val counting = CountingStream(conn.inputStream) { n ->
                if (total > 0) {
                    val p = (n * 100 / total).toInt().coerceAtMost(99)
                    if (p != lastPct) { lastPct = p; onProgress(p) }
                }
            }
            val tar = TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(counting, 64 * 1024)))
            var got = 0
            while (true) {
                val e = tar.nextEntry ?: break
                if (cancel) throw IllegalStateException("已取消")
                if (e.isDirectory) continue
                val name = e.name.substringAfterLast('/')
                if (e.name.contains("test_wavs") || (name != "model.int8.onnx" && name != "tokens.txt")) continue
                val part = File(d, "$name.part")
                part.outputStream().buffered(64 * 1024).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancel) throw IllegalStateException("已取消")
                        val r = tar.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                    }
                }
                if (!part.renameTo(File(d, name))) throw IllegalStateException("無法寫入 $name")
                got++
            }
            if (got < 2 || modelFile(ctx).length() < 100L * 1024 * 1024) {
                throw IllegalStateException("下載的檔案不完整，請重試")
            }
            okFile(ctx).writeText("1")
            onProgress(100)
        } catch (e: Exception) {
            d.deleteRecursively()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // ---------- 辨識 ----------
    private var rec: OfflineRecognizer? = null
    private var recLang: String? = null

    /** lang：""＝自動，"yue"＝粵語，"zh"＝普通話，"en"＝英文。在背景執行緒呼叫。 */
    @Synchronized
    fun transcribe(ctx: Context, samples: FloatArray, lang: String): String {
        if (!installed(ctx)) throw IllegalStateException("尚未下載離線語音模型")
        if (rec == null || recLang != lang) {
            release()
            val cfg = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelFile(ctx).absolutePath,
                        language = lang,
                        useInverseTextNormalization = true
                    ),
                    tokens = tokensFile(ctx).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            )
            rec = OfflineRecognizer(config = cfg)
            recLang = lang
        }
        val r = rec!!
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            return r.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** 釋放模型佔用的記憶體（約數百 MB）。 */
    @Synchronized
    fun release() {
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        recLang = null
    }
}
