package com.danielwkh.weime

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 檢查 GitHub 最新 Release、下載 APK 並交給系統安裝。倉庫必須是公開的。 */
object Updater {
    const val REPO = "danielwkh/wechat-style-ime-scanner"

    data class Release(val tag: String, val apkUrl: String)

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    fun fetchLatest(): Release {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val code = conn.responseCode
            if (code == 404) throw IllegalStateException("找不到 Release（倉庫要設為公開，並且已發佈 v 開頭的標籤）")
            if (code != 200) throw IllegalStateException("伺服器回應 $code")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url")
                    break
                }
            }
            if (url.isEmpty()) throw IllegalStateException("Release 裡沒有 APK 檔案")
            return Release(json.getString("tag_name"), url)
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(s: String) = s.removePrefix("v").split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toInt() }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun download(ctx: Context, url: String, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }          // 清掉舊的／下載到一半的檔案
        val out = File(dir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            if (conn.responseCode != 200) throw IllegalStateException("下載失敗（${conn.responseCode}）")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        verifyApk(ctx, out, total)
        return out
    }

    /** 安裝前先檢查：長度完整、是 ZIP/APK、系統能解析、套件名稱正確。有問題就直接報錯，不交給系統安裝程式。 */
    private fun verifyApk(ctx: Context, f: File, expected: Long) {
        if (expected > 0 && f.length() != expected) {
            f.delete()
            throw IllegalStateException("下載不完整（${f.length()}/$expected 位元組），請關閉 VPN 或換網路後重試")
        }
        val head = ByteArray(2)
        f.inputStream().use { it.read(head) }
        if (head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            val preview = f.readText().take(80)
            f.delete()
            throw IllegalStateException("下載到的不是 APK（內容：$preview）")
        }
        val info = ctx.packageManager.getPackageArchiveInfo(f.absolutePath, 0)
        if (info == null) {
            f.delete()
            throw IllegalStateException("APK 無法解析，檔案可能損毀，請重試")
        }
        if (info.packageName != ctx.packageName) {
            f.delete()
            throw IllegalStateException("APK 套件名稱不符（${info.packageName}）")
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
