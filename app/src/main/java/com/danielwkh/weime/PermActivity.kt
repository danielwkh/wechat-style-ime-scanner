package com.danielwkh.weime

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** 只負責向系統要相機權限（輸入法本身無法彈出授權視窗）。 */
class PermActivity : ComponentActivity() {
    private val req = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(
            this,
            if (ok) "已授權相機，請再按一次「掃描」" else "未授權相機，無法掃描",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) req.launch(Manifest.permission.CAMERA)
    }
}
