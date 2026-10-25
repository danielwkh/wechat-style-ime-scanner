package com.danielwkh.weime

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** 只負責向系統要權限（輸入法本身無法彈出授權視窗）。extra "perm"：camera（預設）或 mic。 */
class PermActivity : ComponentActivity() {
    private val mic get() = intent.getStringExtra("perm") == "mic"

    private val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val msg = if (mic) {
            if (ok) "已授權麥克風，請再按一次麥克風開始說話" else "未授權麥克風，無法語音輸入"
        } else {
            if (ok) "已授權相機，請再按一次「掃描」" else "未授權相機，無法掃描"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            req.launch(if (mic) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA)
        }
    }
}
