package com.danielwkh.weime

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

class ScanActivity : ComponentActivity() {
    private val exec = Executors.newSingleThreadExecutor()
    private val scanner = BarcodeScanning.getClient()
    private var done = false
    private lateinit var pv: PreviewView

    private val perm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pv = PreviewView(this)
        val root = FrameLayout(this)
        root.addView(pv)
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        root.addView(cancel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = 120
        })
        setContentView(root)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            perm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(proxy: ImageProxy) {
        val img = proxy.image
        if (img == null || done) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { list ->
                val value = list.firstNotNullOfOrNull { it.rawValue }
                if (value != null && !done) {
                    done = true
                    ImeService.pending = value
                    finish()
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        exec.shutdown()
    }
}
