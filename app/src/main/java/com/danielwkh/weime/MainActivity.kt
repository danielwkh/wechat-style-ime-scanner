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

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var updateBtn: Button
    private var newer: Updater.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        col.addView(TextView(this).apply {
            text = "1. 按「啟用輸入法」並打開開關\n2. 按「切換輸入法」選擇本輸入法\n3. 按「授權相機」一次；之後鍵盤的「掃描」會在鍵盤範圍內開相機，掃到後自動清除輸入框、貼上並按 Enter"
            textSize = 18f
        })
        col.addView(Button(this).apply {
            text = "啟用輸入法"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "切換輸入法"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
        })
        col.addView(Button(this).apply {
            text = "授權相機"
            setOnClickListener { startActivity(Intent(this@MainActivity, PermActivity::class.java)) }
        })
        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 64, 0, 16)
            text = "目前版本 ${Updater.currentVersion(this@MainActivity)}"
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
