package ai.opennomi.app.screen

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import androidx.core.app.NotificationCompat
import ai.opennomi.app.R
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class ScreenShareService: Service() {
    companion object { const val START="nomi.start";const val STOP="nomi.stop";@Volatile var instance: ScreenShareService?=null }
    private val main=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var worker: HandlerThread?=null;private var handler:Handler?=null
    private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private var display:VirtualDisplay?=null
    private var overlay:OrbitOverlay?=null;private var ocr:LocalOcr?=null;private var session=0L;private var lastFrame=0L
    private var lastTranslation="";private var translationJob:Job?=null;private var translationGeneration=0L;private var captions=CaptionGate();private var playback: PlaybackHearing?=null
    private var width=0;private var height=0
    override fun onBind(intent:Intent?)=null
    override fun onCreate() { super.onCreate();instance=this;ScreenAssistant.init(this) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(intent?.action!=START || projection!=null)return START_NOT_STICKY
        try {
            val data=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra("grant",Intent::class.java) else @Suppress("DEPRECATION") (intent.getParcelableExtra("grant") as? Intent)
            requireNotNull(data){"需要重新授权屏幕共享"}
            val channel=NotificationChannel("screen-share","OpenNomi 屏幕共享",NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            val open=PendingIntent.getActivity(this,0,Intent(this,WorkspaceActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop=PendingIntent.getService(this,1,Intent(this,ScreenShareService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification=NotificationCompat.Builder(this,"screen-share").setSmallIcon(R.drawable.ic_nomi).setContentTitle("OpenNomi 正在共享屏幕").setContentText("点击查看 · 随时停止共享").setContentIntent(open).setOngoing(true).addAction(0,"停止",stop).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(402,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(402,notification)
            session=ScreenState.begin();captions=CaptionGate();lastTranslation="";lastFrame=0L
            worker=HandlerThread("nomi-screen-ocr").apply{start()};handler=Handler(worker!!.looper);ocr=LocalOcr(this)
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
            projection!!.registerCallback(object: MediaProjection.Callback(){override fun onStop(){stopSelf()}},main)
            val dm=resources.displayMetrics; val ratio=minOf(1f,1200f/dm.widthPixels);width=(dm.widthPixels*ratio).toInt();height=(dm.heightPixels*ratio).toInt()
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
            reader!!.setOnImageAvailableListener({ r -> capture(r) },handler)
            display=projection!!.createVirtualDisplay("NomiScreen",width,height,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            overlay=OrbitOverlay(this);overlay!!.show()
            scope.launch { ScreenState.state.collect { overlay?.render(it) } }
            scope.launch { while(isActive){captions.tick(System.currentTimeMillis())?.let{line->ScreenState.update{it.copy(caption=line)}};delay(400)} }
        }catch(t:Throwable){ScreenState.event("无法开启共享：${t.message}");stopSelf()}
        return START_NOT_STICKY
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        handler?.post {
            if(!ScreenState.valid(session))return@post
            try {
                val dm=resources.displayMetrics;val ratio=minOf(1f,1200f/dm.widthPixels)
                val w=(dm.widthPixels*ratio).toInt();val h=(dm.heightPixels*ratio).toInt()
                if(w==width && h==height)return@post
                val old=reader;val next=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2)
                width=w;height=h;reader=next
                next.setOnImageAvailableListener({r->capture(r)},handler)
                display?.resize(w,h,dm.densityDpi);display?.surface=next.surface;old?.close()
                ScreenState.frame=null
            }catch(t:Throwable){ScreenState.event("屏幕方向变化后请重新开启共享")}
        }
    }
    private fun capture(r: ImageReader) {
        val image=runCatching{r.acquireLatestImage()}.getOrNull() ?: return
        var bitmap: Bitmap?=null
        try {
            val now=System.currentTimeMillis();if(ScreenState.ownForeground || !ScreenState.valid(session) || now-lastFrame<2400)return
            lastFrame=now
            val plane=image.planes[0];val rowWidth=plane.rowStride/plane.pixelStride
            val padded=Bitmap.createBitmap(rowWidth,height,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(plane.buffer)
            bitmap=Bitmap.createBitmap(padded,0,0,width,height);if(bitmap!==padded)padded.recycle()
            overlay?.mask(bitmap)
            val page=ScreenState.state.value.page
            // No visual upload or OCR when an accessible password field is present.
            if(page.sensitive){ScreenState.frame=null;return}
            val encoded=ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.JPEG,75,out);out.toByteArray()}
            if(!ScreenState.valid(session))return
            val local=if(page.text.isBlank() || ScreenAccessService.instance==null)ocr?.recognize(bitmap).orEmpty()else ""
            if(!ScreenState.valid(session))return
            if(page.text.isBlank() || ScreenAccessService.instance==null) ScreenState.update { it.copy(page=Page(page.app.ifBlank{"屏幕 · OCR"},local,version=if(page.text==local)page.version else page.version+1)) }
            val observed=ScreenState.state.value.page
            if(observed.app==page.app || ScreenAccessService.instance==null)ScreenState.frame=ScreenFrame(encoded,width,height,now,session,observed.app,observed.version)
            if(ScreenState.state.value.translation && !ScreenState.state.value.audio) translateLine((page.text.ifBlank{local}).lineSequence().filter{it.isNotBlank()}.takeLastLines(5).joinToString("\n").take(1000))
        }catch(t:Throwable){if(ScreenState.valid(session))ScreenState.event("屏幕读取暂不可用：${t.message ?: "请重新开启共享"}")}
        finally{bitmap?.recycle();image.close()}
    }
    private fun Sequence<String>.takeLastLines(n:Int)=toList().takeLast(n)
    fun refreshOverlay(){overlay?.show()}
    fun toggleTranslation() {
        val on=!ScreenState.state.value.translation
        if(on && !ScreenAssistant.settings().modelReady() && ScreenAssistant.settings().libre.isBlank() && (application as ai.opennomi.app.NomiApplication).cloudModel.connected.value.not()){ScreenState.event("先连接首页 NOMI，或在模型连接中填写翻译服务地址");return}
        translationGeneration++;translationJob?.cancel();translationJob=null
        captions=CaptionGate();lastTranslation=""
        if(!on){playback?.close();playback=null}
        ScreenState.update { it.copy(translation=on,audio=if(on)it.audio else false,caption="",notice="",status=if(on)"屏幕翻译已开启" else "屏幕翻译已暂停") }
    }
    fun translateLine(line:String) {
        if(Looper.myLooper()!=main.looper){main.post{translateLine(line)};return}
        if(line.isBlank() || line==lastTranslation || ScreenState.state.value.busy || !ScreenState.valid(session) || !ScreenState.state.value.translation)return
        if(translationJob?.isActive==true)return
        val id=session;val generation=translationGeneration
        translationJob=scope.launch {
            try{ val text=withContext(Dispatchers.IO){ScreenAssistant.translate(line)};if(ScreenState.valid(id)&&generation==translationGeneration&&ScreenState.state.value.translation){lastTranslation=line;captions.offer("$line\n\n$text",System.currentTimeMillis())?.let{result->ScreenState.update{it.copy(caption=result)}};MemoryStore(this@ScreenShareService).use{it.add("translation","$line\n$text","实时翻译")}} }
            catch(e:CancellationException){throw e}catch(t:Throwable){
                if(ScreenState.valid(id) && generation==translationGeneration) {
                    val error="翻译已暂停：${t.message ?: "连接未完成"}。点球球打开工作台检查连接后重试。"
                    playback?.close();playback=null;lastTranslation=""
                    ScreenState.update{it.copy(translation=false,audio=false,notice=error)}
                    ScreenState.event(error)
                }
            }
            finally{if(generation==translationGeneration)translationJob=null}
        }
    }
    fun toggleAudio() {
        if(ScreenState.state.value.audio){playback?.close();playback=null;ScreenState.update{it.copy(audio=false,status="已切回屏幕文字翻译")};return}
        if(!ScreenState.state.value.translation)toggleTranslation()
        if(!ScreenState.state.value.translation)return
        val p=projection ?: return
        try{playback=PlaybackHearing(this,p,{text-> main.post{translateLine(text)}},{error->main.post{if(ScreenState.valid(session)){ScreenState.update{it.copy(audio=false,notice="视频声音识别已停止：$error")};ScreenState.event(error)}}});playback!!.start();ScreenState.update{it.copy(audio=true,notice="",status="视频声音识别中 · 英语离线模型")}}
        catch(t:Throwable){playback?.close();playback=null;ScreenState.event("声音识别无法启动：${t.message}")}
    }
    override fun onDestroy() {
        instance=null;ScreenAssistant.stop();ScreenState.end();scope.cancel();overlay?.close();overlay=null
        playback?.close();playback=null
        val p=projection;projection=null;runCatching{display?.release()};display=null;runCatching{reader?.close()};reader=null;runCatching{p?.stop()}
        // Queue native OCR cleanup behind any in-flight recognition.
        handler?.post{ocr?.close();ocr=null;worker?.quitSafely()};stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
