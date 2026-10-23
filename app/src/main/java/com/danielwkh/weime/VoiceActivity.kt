package com.danielwkh.weime

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** 呼叫系統語音辨識（不需要麥克風權限），結果交給輸入法送出。 */
class VoiceActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !text.isNullOrEmpty()) ImeService.pending = text
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "請說話")
        }
        try {
            launcher.launch(i)
        } catch (e: Exception) {
            Toast.makeText(this, "找不到語音輸入服務（需要 Google 應用）", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
