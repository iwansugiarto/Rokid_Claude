package com.rokid.relayhud

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

/** 拍照给 Claude 看:取景 + 单击拍一张,写入 photos/pending.jpg,MainActivity onResume 接手发送。 */
class PhotoActivity : ComponentActivity() {
    private val s by lazy { strings(loadLang()) }
    private val status = mutableStateOf("")
    private var capture: ImageCapture? = null
    @Volatile private var shooting = false             // 防连击:一次快门在途时吞掉后续 TAP

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else { status.value = s.cameraDenied; finish() }
        }

    private fun loadLang(): String = try {
        val f = File(getExternalFilesDir(null), "config.json")
        if (f.exists()) parseConfig(f.readText()).lang else "zh"
    } catch (_: Exception) { "zh" }

    /** MainActivity 消费的交接文件(固定名,后写覆盖先写)。 */
    private fun pendingFile(): File =
        File(getExternalFilesDir("photos"), "pending.jpg")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status.value = s.photoHint
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val previewView = PreviewView(this)
        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Text(
                    status.value, color = Color(0xFF00FF88), fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cap = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            capture = cap
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun shoot() {
        val cap = capture ?: return
        if (shooting) return
        shooting = true
        val out = pendingFile()
        out.parentFile?.mkdirs()
        out.delete()   // 旧 adb push 残留可能属主不同,先删再写(同 config.json 的做法)
        val opts = ImageCapture.OutputFileOptions.Builder(out).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(res: ImageCapture.OutputFileResults) { finish() }
            override fun onError(e: ImageCaptureException) {
                shooting = false
                status.value = s.photoFailed
            }
        })
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true }   // 双击=取消
        if (Gestures.map(keyCode, KeyEvent.ACTION_UP) == GestureAction.TAP) { shoot(); return true }
        return super.onKeyUp(keyCode, event)
    }
}
