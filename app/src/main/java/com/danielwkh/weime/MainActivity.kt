package com.danielwkh.weime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        col.addView(TextView(this).apply {
            text = "1. 按「啟用輸入法」並打開開關\n2. 按「切換輸入法」選擇本輸入法\n3. 按「授權相機」一次；之後鍵盤的「掃描」會在鍵盤範圍內開相機，掃到後自動清除輸入框、貼上並按 Enter\n\n鍵盤頂部工具欄：📋剪貼板、繁／简切換、😀表情、🎤語音、⚙設定"
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
        col.addView(Button(this).apply {
            text = "設定（匯入商品庫、檢查更新）"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        setContentView(col)
    }
}
