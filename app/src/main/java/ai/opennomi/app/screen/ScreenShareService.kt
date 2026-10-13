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
import kotlinx.coroutines.flow.collect
import java.io.ByteArrayOutputStream

class ScreenShareService: Service() {
    companion object { const val START="nomi.start";const val STOP="nomi.stop";@Volatile var instance: ScreenShareService?=null }
    private val main=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var worker: HandlerThread?=null;private var handler:Handler?=null
    private var projection:MediaProjection?=null;private var reader:ImageReader?=null;private var display:VirtualDisplay?=null
    private var overlay:OrbitOverlay?=null;private var ocr:LocalOcr?=null;private var session=0L;private var lastFrame=0L
    private val translations=TranslationBuffer();private var translationConfig="";private var translationJob:Job?=null;private var translationGeneration=0L;private var captions=CaptionGate();private var playback: PlaybackHearing?=null
    private var width=0;private var height=0
    private val audioQueue=AudioSubtitleQueue();private var audioSource="en";private var audioContext=""
    private val audioCaptions=AudioCaptionPresenter()
    private var playbackGeneration=0L;private var audioPreparation:Job?=null
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
            session=ScreenState.begin();captions=CaptionGate();translations.reset();translationConfig=ScreenAssistant.settings().translationFingerprint();lastFrame=0L
            worker=HandlerThread("nomi-screen-ocr").apply{start()};handler=Handler(worker!!.looper);ocr=LocalOcr(this)
            projection=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data)
            projection!!.registerCallback(object: MediaProjection.Callback(){override fun onStop(){ScreenState.event("系统已结束屏幕共享，请重新授权开启");stopSelf()}},main)
            val dm=resources.displayMetrics; val ratio=minOf(1f,1200f/dm.widthPixels);width=(dm.widthPixels*ratio).toInt();height=(dm.heightPixels*ratio).toInt()
            reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2)
            reader!!.setOnImageAvailableListener({ r -> capture(r) },handler)
            display=projection!!.createVirtualDisplay("NomiScreen",width,height,dm.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader!!.surface,null,handler)
            overlay=OrbitOverlay(this);overlay!!.show()
            scope.launch { ScreenState.state.collect { overlay?.render(it) } }
            scope.launch { while(isActive){
                if(ScreenState.state.value.audio){audioCaptions.tick(SystemClock.elapsedRealtime())?.let{showAudioCaption(it)};updateAudioQueueState()}
                else captions.tick(System.currentTimeMillis())?.let{line->ScreenState.update{it.copy(caption=line)}}
                pumpTranslation();delay(250)
            } }
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
        if(!ScreenState.valid(session))return
        val image=runCatching{r.acquireLatestImage()}.getOrNull() ?: return
        var bitmap: Bitmap?=null
        try {
            val state=ScreenState.state.value
            val now=System.currentTimeMillis();if(ScreenState.ownForeground || !ScreenState.valid(session) || now-lastFrame<ScreenCapturePacing.intervalMs(state.translation,state.audio,state.busy,state.agentRunning))return
            lastFrame=now
            val plane=image.planes[0];val rowWidth=plane.rowStride/plane.pixelStride
            val padded=Bitmap.createBitmap(rowWidth,image.height,Bitmap.Config.ARGB_8888);padded.copyPixelsFromBuffer(plane.buffer)
            bitmap=Bitmap.createBitmap(padded,0,0,image.width,image.height);if(bitmap!==padded)padded.recycle()
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
            if(ScreenState.state.value.translation && !ScreenState.state.value.audio) translateLine(TranslationText.foreignLines(page.text.ifBlank{local}))
        }catch(t:Throwable){if(ScreenState.valid(session))ScreenState.event("屏幕读取暂不可用：${t.message ?: "请重新开启共享"}")}
        finally{bitmap?.recycle();image.close()}
    }
    private fun Sequence<String>.takeLastLines(n:Int)=toList().takeLast(n)
    fun refreshOverlay(){overlay?.show();overlay?.render(ScreenState.state.value)}
    fun toggleTranslation() {
        val on=!ScreenState.state.value.translation
        if(on && !ScreenAssistant.settings().translationReady()){ScreenState.event("请在翻译页开启手机翻译，或填写模型／翻译服务");return}
        translationGeneration++;translationJob?.cancel();translationJob=null
        captions=CaptionGate();translations.reset();audioQueue.reset();audioCaptions.reset();audioContext=""
        if(!on){playbackGeneration++;audioPreparation?.cancel();audioPreparation=null;playback?.close();playback=null}
        ScreenState.update { it.copy(translation=on,audio=if(on)it.audio else false,caption="",captionOriginal="",audioPreview="",notice="",status=if(on)"屏幕翻译已开启" else "屏幕翻译已暂停") }
    }
    fun translateLine(line:String) {
        if(Looper.myLooper()!=main.looper){main.post{translateLine(line)};return}
        if(!ScreenState.valid(session) || !ScreenState.state.value.translation)return
        val cfg=ScreenAssistant.settings()
        val config=cfg.translationFingerprint()
        if(config!=translationConfig){translations.reset();audioQueue.reset();audioCaptions.reset();audioContext="";translationConfig=config}
        translations.offer(line)
        pumpTranslation()
    }
    private fun pumpTranslation() {
        if(translationJob?.isActive==true || !ScreenState.valid(session) || !ScreenState.state.value.translation)return
        val currentConfig=ScreenAssistant.settings().translationFingerprint()
        if(currentConfig!=translationConfig){translations.reset();audioQueue.reset();audioCaptions.reset();audioContext="";translationConfig=currentConfig}
        val audio=ScreenState.state.value.audio
        if(audio && !audioCaptions.canAccept)return
        val utterance=if(audio)audioQueue.nextBatch(SystemClock.elapsedRealtime()) else null
        val line=if(audio)utterance?.text ?: return else translations.next(System.currentTimeMillis()) ?: return
        val id=session;val generation=translationGeneration;val config=translationConfig
        translationJob=scope.launch {
            val modelProgress=if(!audio && ScreenAssistant.settings().phoneTranslation)launch {
                ScreenAssistant.phoneTranslator.status.collect { progress ->
                    if(ScreenState.valid(id) && generation==translationGeneration && ScreenState.state.value.translation)
                        ScreenState.update{it.copy(notice=progress)}
                }
            } else null
            try {
                val text=(if(audio)null else translations.cached(line)) ?: withContext(Dispatchers.IO){ScreenAssistant.translate(line,if(audio)audioSource else null,if(audio)audioContext else "")}
                if(ScreenState.valid(id) && generation==translationGeneration && config==ScreenAssistant.settings().translationFingerprint() && ScreenState.state.value.translation){
                    if(audio && utterance!=null) {
                        val caption=AudioCaption(text,line,utterance.confidence,PlaybackModels.selected(ScreenAssistant.settings().playbackModel).label)
                        val displayed=audioCaptions.offer(caption,SystemClock.elapsedRealtime())
                        audioQueue.success(utterance)
                        updateAudioQueueState()
                        audioContext=(audioContext+"\n"+line).takeLast(600)
                        ScreenState.update{it.copy(captionHistory=(it.captionHistory+caption).takeLast(20))}
                        displayed?.let{showAudioCaption(it)}
                    } else {
                        translations.success(line,text)
                        captions.offer(text,System.currentTimeMillis())?.let{result->ScreenState.update{it.copy(caption=result,captionOriginal="")}}
                        ScreenState.update{it.copy(notice="",status="实时翻译中")}
                    }
                }
            } catch(e:CancellationException){throw e}
            catch(t:Throwable){
                if(ScreenState.valid(id) && generation==translationGeneration){
                    if(audio)audioQueue.failed(SystemClock.elapsedRealtime()) else translations.failed(System.currentTimeMillis())
                    val error="翻译暂未完成：${t.message ?: "网络不可用"} · 正在等待重试，翻译保持开启"
                    ScreenState.update{it.copy(notice=error)};ScreenState.event(error)
                }
            } finally {
                modelProgress?.cancel()
                if(generation==translationGeneration){translationJob=null;if(audio)main.post{pumpTranslation()}}
            }
        }
    }
    private fun showAudioCaption(caption:AudioCaption) {
        val warning=when {
            caption.confidence==null -> "这句没有可靠识别置信度，请对照原文"
            caption.confidence<.55 -> "这句包含不确定的识别词，请对照原文"
            audioQueue.size>=4 || audioCaptions.size>0 -> "字幕正在追赶播放进度；漏读可看近期译文"
            else -> ""
        }
        ScreenState.update{it.copy(caption=caption.translated,captionOriginal=caption.original,notice=warning,status="视频实时字幕 · ${caption.language}")}
    }
    private fun updateAudioQueueState(){ScreenState.update{it.copy(audioQueued=audioQueue.size,captionQueued=audioCaptions.size)}}
    fun resyncAudio(){if(ScreenState.state.value.audio)toggleAudio(restart=true)}
    fun toggleAudio(restart:Boolean=false) {
        playbackGeneration++;val generation=playbackGeneration
        translationGeneration++;translationJob?.cancel();translationJob=null
        audioPreparation?.cancel();audioPreparation=null;playback?.close();playback=null
        translations.reset();audioQueue.reset();audioCaptions.reset();audioContext="";captions=CaptionGate()
        updateAudioQueueState()
        if(ScreenState.state.value.audio && !restart){ScreenState.pauseVideo();return}
        if(!ScreenState.state.value.translation)toggleTranslation()
        if(!ScreenState.state.value.translation)return
        if(Build.VERSION.SDK_INT<29){ScreenState.event("无字幕视频声音翻译需要 Android 10 以上");return}
        val p=projection ?: return
        val cfg=ScreenAssistant.settings();val model=PlaybackModels.selected(cfg.playbackModel)
        audioSource=model.language;translationConfig=cfg.translationFingerprint()
        val id=session
        fun valid()=ScreenState.valid(id) && playbackGeneration==generation && ScreenState.state.value.audio
        ScreenState.update{it.copy(audio=true,caption="",captionOriginal="",audioPreview="",notice="正在准备原声识别与翻译模型，请准备好后播放视频",status="正在准备${model.label}视频字幕")}
        audioPreparation=scope.launch {
            try {
                val root=withContext(Dispatchers.IO){PlaybackModels.prepare(this@ScreenShareService,model,cfg.translationCellular,{valid()},{progress->main.post{if(valid())ScreenState.update{it.copy(notice=progress)}}})}
                if(cfg.phoneTranslation) {
                    val progress=launch{ScreenAssistant.phoneTranslator.status.collect{message->if(valid())ScreenState.update{it.copy(notice=message)}}}
                    try{ScreenAssistant.phoneTranslator.prepare(model.language,cfg.translationCellular)}finally{progress.cancel()}
                }
                if(!valid())return@launch
                playback=PlaybackHearing(this@ScreenShareService,p,root,SubtitleTiming.selected(cfg.subtitleTiming),
                    {text,confidence->main.post{if(valid()){
                        if(!audioQueue.offer(text,confidence))ScreenState.update{it.copy(notice="翻译速度暂时跟不上；这句未加入字幕，请降低播放速度后重试")}
                        updateAudioQueueState()
                        pumpTranslation()
                    }}},
                    {text->main.post{if(valid())ScreenState.update{it.copy(audioPreview=text)}}},
                    {message->main.post{if(valid()){ScreenState.update{it.copy(notice=message)};ScreenState.event(message)}}},
                    {main.post{if(valid()){translationGeneration++;translationJob?.cancel();translationJob=null;audioQueue.reset();audioCaptions.reset();ScreenState.pauseVideo(notice=ScreenState.state.value.notice,status="视频声音捕获已停止，请重新开启字幕")}}},
                    {main.post{if(valid())ScreenState.update{it.copy(notice="",status="${model.label}视频字幕已就绪 · 请播放无字幕视频")}}}
                )
                ScreenState.update{it.copy(notice="正在加载原声识别器，请就绪后播放视频")}
                playback!!.start()
            }catch(e:CancellationException){throw e}
            catch(t:Throwable){if(valid()){ScreenState.pauseVideo(notice=t.message ?: "视频字幕准备失败",status="视频字幕准备失败，请检查语言模型和音频权限后重试")}}
            finally {if(playbackGeneration==generation)audioPreparation=null}
        }
    }
    override fun onDestroy() {
        instance=null;audioQueue.reset();audioCaptions.reset();playbackGeneration++;audioPreparation?.cancel();audioPreparation=null;ScreenAssistant.stop();ScreenState.end();scope.cancel();overlay?.close();overlay=null
        ScreenAssistant.phoneTranslator.clearCachedTranslations()
        playback?.close();playback=null
        val p=projection;projection=null
        val screenWorker=worker
        // ImageReader.close invalidates even acquired plane buffers. Serialize all capture
        // resources behind in-flight pixel copies and OCR, including a queued resize.
        val cleanup=Runnable {
            runCatching{reader?.setOnImageAvailableListener(null,null)}
            runCatching{display?.release()};display=null
            runCatching{reader?.close()};reader=null
            runCatching{ocr?.close()};ocr=null
            runCatching{p?.stop()}
            screenWorker?.quitSafely()
        }
        if(handler?.post(cleanup)!=true)cleanup.run()
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
