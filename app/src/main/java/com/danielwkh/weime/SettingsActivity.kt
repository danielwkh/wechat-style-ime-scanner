package com.danielwkh.weime

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var updateBtn: Button
    private var newer: Updater.Release? = null
    private lateinit var dbStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "設定"
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        col.addView(TextView(this).apply {
            text = "商品庫"
            textSize = 20f
        })
        dbStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, 16, 0, 8)
            text = "商品庫：${ProductDb.count(this@SettingsActivity)} 筆（匯入會補全：相同的略過、沒有的新增）"
        }
        col.addView(dbStatus)
        col.addView(Button(this).apply {
            text = "匯入商品庫 (xlsx)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                    1001
                )
            }
        })
        col.addView(TextView(this).apply {
            text = "版本更新"
            textSize = 20f
            setPadding(0, 64, 0, 0)
        })
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 16, 0, 16)
            text = "目前版本 ${Updater.currentVersion(this@SettingsActivity)}"
        }
        col.addView(status)
        updateBtn = Button(this).apply {
            text = "檢查更新"
            setOnClickListener { onUpdateClick() }
        }
        col.addView(updateBtn)
        setContentView(col)
        checkUpdate()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1001 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        dbStatus.text = "匯入中…"
        Thread {
            try {
                val r = ProductDb.import(this, uri)
                runOnUiThread {
                    dbStatus.text = "商品庫：共 ${r.count} 筆（新增 ${r.added}、更新名稱 ${r.updated}、相同略過 ${r.skipped}）\n名稱欄＝${r.nameLabel}；條碼欄＝${r.codeLabels}"
                }
            } catch (e: Exception) {
                runOnUiThread { dbStatus.text = "匯入失敗：${e.message}" }
            }
        }.start()
    }

    private fun onUpdateClick() {
        val r = newer
        if (r == null) checkUpdate() else startDownload(r)
    }

    private fun checkUpdate() {
        val cur = Updater.currentVersion(this)
        status.text = "目前版本 $cur，檢查更新中…"
        Thread {
            try {
                val r = Updater.fetchLatest()
                runOnUiThread {
                    if (Updater.isNewer(r.tag, cur)) {
                        newer = r
                        status.text = "發現新版本 ${r.tag}（目前 $cur）"
                        updateBtn.text = "下載並安裝"
                    } else {
                        newer = null
                        status.text = "已是最新版本（$cur）"
                        updateBtn.text = "檢查更新"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "目前版本 $cur。檢查更新失敗：${e.message}" }
            }
        }.start()
    }

    private fun startDownload(r: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "請先允許本 App 安裝應用程式，返回後再按一次「下載並安裝」"
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        updateBtn.isEnabled = false
        Thread {
            try {
                val f = Updater.download(this, r.apkUrl) { p ->
                    runOnUiThread { status.text = "下載中… $p%" }
                }
                runOnUiThread {
                    status.text = "下載完成，請在彈出畫面按「安裝」"
                    updateBtn.isEnabled = true
                    Updater.install(this, f)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "下載失敗：${e.message}"
                    updateBtn.isEnabled = true
                }
            }
        }.start()
    }
}
