package ai.opennomi.app.voice

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ai.opennomi.app.screen.ScreenAccessService
import kotlinx.coroutines.*
import java.io.File

/** CameraX (Apache-2.0): preview and actual capture, without a manual camera-app shutter. */
object VoiceCamera {
    var activity: VoiceCameraActivity? = null; private set
    private var ready = CompletableDeferred<VoiceCameraActivity>()
    fun attach(value: VoiceCameraActivity) { activity=value; if(!ready.isCompleted)ready.complete(value) }
    fun detach(value: VoiceCameraActivity) { if(activity===value)activity=null; if(!ready.isCompleted)ready.completeExceptionally(IllegalStateException("相机已退出")) }
    suspend fun open(context: Context): VoiceCameraActivity {
        check(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED) { "相机权限未开启，请在首页允许相机权限" }
        activity?.let { return it }
        ready=CompletableDeferred()
        val intent=Intent(context,VoiceCameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        (ScreenAccessService.instance ?: context).startActivity(intent)
        return withTimeout(10000) { ready.await() }
    }
    fun close(): Boolean { val current=activity ?: return false; current.finish(); return true }
}

class VoiceCameraActivity: ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var label: TextView
    private var provider: ProcessCameraProvider?=null
    private var capture: ImageCapture?=null
    private var boundCamera:Camera?=null
    private var facing=CameraSelector.LENS_FACING_BACK
    private var saving=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.BLACK) }
        label=TextView(this).apply { text="OpenNomi 语音相机 · 说拍照、自拍或退出相机"; setTextColor(android.graphics.Color.WHITE); setPadding(20,24,20,24) }
        preview=PreviewView(this)
        layout.addView(label);layout.addView(preview,LinearLayout.LayoutParams(-1,0,1f))
        layout.addView(Button(this).apply { text="拍照";setOnClickListener { lifecycleScope.launch { runCatching { takePhoto() }.onSuccess { label.text=it }.onFailure { label.text=it.message } } } })
        layout.addView(Button(this).apply { text="退出相机";setOnClickListener { finish() } })
        setContentView(layout)
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) { label.text="相机权限未开启";finish();return }
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            if(isFinishing || isDestroyed)return@addListener
            runCatching { provider=future.get();bindCamera();VoiceCamera.attach(this) }.onFailure { label.text="相机启动失败：${it.message}" }
        },ContextCompat.getMainExecutor(this))
    }
    private fun bindCamera() {
        val p=checkNotNull(provider)
        val selector=CameraSelector.Builder().requireLensFacing(facing).build()
        check(p.hasCamera(selector)) { "这台手机没有对应的摄像头" }
        val output=ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        val view=Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        p.unbindAll();boundCamera=p.bindToLifecycle(this,selector,view,output);capture=output
    }
    suspend fun torch(enabled:Boolean) {
        val camera=checkNotNull(boundCamera) { "相机还未准备好" }
        check(camera.cameraInfo.hasFlashUnit()) { "当前摄像头没有手电筒，请切换到后置摄像头" }
        val future=camera.cameraControl.enableTorch(enabled)
        withTimeout(4000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                future.addListener({runCatching { future.get() }.onSuccess { if(continuation.isActive)continuation.resumeWith(Result.success(Unit)) }.onFailure { if(continuation.isActive)continuation.resumeWith(Result.failure(it)) }},ContextCompat.getMainExecutor(this@VoiceCameraActivity))
            }
            while(camera.cameraInfo.torchState.value!=if(enabled)TorchState.ON else TorchState.OFF)delay(50)
        }
    }
    fun switchCamera(selfie:Boolean=false) {
        check(!saving) { "正在保存照片，请稍后切换镜头" }
        val previous=facing
        facing=if(selfie)CameraSelector.LENS_FACING_FRONT else if(facing==CameraSelector.LENS_FACING_BACK)CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        try { bindCamera() } catch(e:Exception) { facing=previous;bindCamera();throw e }
    }
    suspend fun takePhoto(): String {
        check(!saving && !isFinishing) { "相机正在忙，请稍后再拍" }
        val camera=checkNotNull(capture) { "相机还未准备好" }
        saving=true
        val name="OpenNomi_${System.currentTimeMillis()}.jpg"
        val output=if(Build.VERSION.SDK_INT>=29) {
            val values=ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/OpenNomi") }
            ImageCapture.OutputFileOptions.Builder(contentResolver,MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values).build()
        } else ImageCapture.OutputFileOptions.Builder(File(getExternalFilesDir(Environment.DIRECTORY_PICTURES),name)).build()
        return try {
            withTimeout(15000) {
                suspendCancellableCoroutine { continuation ->
                    camera.takePicture(output,ContextCompat.getMainExecutor(this@VoiceCameraActivity),object:ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result:ImageCapture.OutputFileResults) {
                            saving=false;label.text="照片已保存 · $name"
                            ai.opennomi.app.screen.ScreenState.event("照片已保存 · $name")
                            if(continuation.isActive)continuation.resumeWith(Result.success(if(Build.VERSION.SDK_INT>=29)"照片已保存到相册的 OpenNomi 文件夹。" else "照片已保存到 OpenNomi 照片文件夹。"))
                        }
                        override fun onError(exception:ImageCaptureException) {
                            saving=false;label.text="拍照失败：${exception.message}"
                            if(continuation.isActive)continuation.resumeWith(Result.failure(exception))
                        }
                    })
                }
            }
        } finally { saving=false }
    }
    override fun onStop() { VoiceCamera.detach(this);provider?.unbindAll();capture=null;boundCamera=null;super.onStop();if(!isChangingConfigurations)finish() }
}
