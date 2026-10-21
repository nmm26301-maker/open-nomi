package ai.opennomi.app

import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoSubtitleSmokeTest {
    @Test fun missingOverlayPermissionIsResolvedBeforeSharingStarts()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance?.stopSelf()}
        withTimeout(10000){while(ScreenState.state.value.active)delay(100)}
        device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
        if(android.os.Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW deny")
        try {
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation")).use {
                clickVideoStart(device)
                assertTrue("Missing overlay permission must open its system settings",device.wait(Until.hasObject(By.pkg("com.android.settings")),10000))
                assertFalse(ScreenState.state.value.active);assertFalse(ScreenState.state.value.audio)
                device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
                repeat(3){if(device.currentPackageName=="com.android.settings"){device.pressBack();delay(500)}}
                // SystemUI uses a negative button on API 29 and a neutral cancel button on API 33.
                val grant=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile("android:id/button[23]"))),10000)
                assertNotNull("Returning after overlay approval must continue to projection permission: ${ScreenState.state.value.status}, package=${device.currentPackageName}",grant)
                grant.click();delay(500)
                assertFalse("Denying sharing must not start capture",ScreenState.state.value.active)
            }
        } finally {device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")}
    }
    @Test fun fastCapturedPlayerAudioProducesChineseWithoutAnyScreenSubtitle()=capturedPlayer(SubtitleTiming.FAST)
    @Test fun completeCapturedPlayerAudioProducesChineseWithoutAnyScreenSubtitle()=capturedPlayer(SubtitleTiming.COMPLETE)
    private fun capturedPlayer(timing:SubtitleTiming)=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val cfg=ScreenAssistant.settings();val old=cfg.playbackModel;val phone=cfg.phoneTranslation;val cellular=cfg.translationCellular
        val oldTiming=cfg.subtitleTiming
        val oldOverlay=cfg.subtitleOverlay;val oldPosition=cfg.subtitlePosition;val oldOriginal=cfg.subtitleOriginal;val oldLarge=cfg.subtitleLarge
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio=app.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            cfg.playbackModel="en";cfg.phoneTranslation=true;cfg.translationCellular=true
            cfg.subtitleOverlay=true;cfg.subtitleOriginal=true;cfg.subtitleLarge=false;cfg.subtitlePosition=SubtitlePosition.BOTTOM.name
            cfg.subtitleTiming=timing.name
            withContext(Dispatchers.IO){PlaybackModels.prepare(app,PlaybackModels.selected("en"),true)}
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("en",true)}
            device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            if(android.os.Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0)
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation")).use { scenario ->
                clickVideoStart(device)
                val grant=device.wait(Until.findObject(By.res("android:id/button1")),10000)
                assertNotNull("MediaProjection permission dialog must appear",grant);grant.click()
                withTimeout(60000){while(!ScreenState.state.value.audio || !ScreenState.state.value.status.contains("已就绪"))delay(200)}
                val captureStarted=SystemClock.elapsedRealtime()
                device.executeShellCommand("am start -n ai.opennomi.playbackfixture/.PlaybackFixtureActivity")
                if(timing==SubtitleTiming.FAST) {
                    withTimeout(60000){while(ScreenState.state.value.captionDraft.isBlank()) {
                        assertTrue("A useful provisional result must arrive before the first final caption",ScreenState.state.value.captionHistory.isEmpty())
                        delay(20)
                    }}
                    val draft=ScreenState.state.value
                    assertTrue(draft.captionDraft.any{it in '\u4e00'..'\u9fff'})
                    assertTrue("Draft must not enter recent confirmed translations",draft.captionHistory.isEmpty())
                    assertEquals("ai.opennomi.playbackfixture",device.currentPackageName)
                    var visibleDraft=""
                    withTimeout(10000){while(visibleDraft.isBlank()) {
                        InstrumentationRegistry.getInstrumentation().runOnMainSync{
                            val service=ScreenShareService.instance!!
                            val overlay=ScreenShareService::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(service)
                            val view=OrbitOverlay::class.java.getDeclaredField("caption").apply{isAccessible=true}.get(overlay) as? android.widget.TextView
                            if(view?.isShown==true && view.isAttachedToWindow && view.width>0 && view.height>0 && view.layout?.text?.toString()==view.text.toString() && view.text.contains("临时译文") && view.text.contains(draft.captionDraft)) {
                                val end=view.text.indexOf(draft.captionDraft)+draft.captionDraft.length
                                assertTrue("Provisional Chinese must fit in the rendered overlay lines",view.layout.getLineForOffset(end)<=view.maxLines-1)
                                visibleDraft=view.text.toString()
                                val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                                try{view.draw(android.graphics.Canvas(bitmap));java.io.FileOutputStream(java.io.File(app.getExternalFilesDir(null),"background-preview.png")).use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
                            }
                        }
                        if(visibleDraft.isBlank())delay(20)
                    }}
                    device.executeShellCommand("cp ${java.io.File(app.getExternalFilesDir(null),"background-preview.png").absolutePath} /data/local/tmp/background-preview.png")
                    val report=org.json.JSONObject().put("first_draft_elapsed_ms",SystemClock.elapsedRealtime()-captureStarted)
                        .put("source",draft.captionDraftOriginal).put("draft",draft.captionDraft).put("overlay",visibleDraft)
                    val output=java.io.File(app.getExternalFilesDir(null),"translation-preview.json")
                    output.writeText(report.toString(2))
                    device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/translation-preview.json")
                }
                withTimeout(60000){while(!ScreenState.state.value.captionOriginal.contains("zero") || !ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})delay(200)}
                if(timing==SubtitleTiming.FAST) {
                    val output=java.io.File(app.getExternalFilesDir(null),"translation-preview.json")
                    val report=org.json.JSONObject(output.readText()).put("first_confirmed_elapsed_ms",SystemClock.elapsedRealtime()-captureStarted)
                    assertTrue(report.getLong("first_draft_elapsed_ms")<report.getLong("first_confirmed_elapsed_ms"))
                    output.writeText(report.toString(2));device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/translation-preview.json")
                }
                assertTrue(ScreenState.state.value.audio)
                assertTrue(ScreenState.state.value.captionOriginal.contains("zero"))
                assertTrue(ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})
                val recorded=ScreenState.state.value.captionHistory.last{it.original.contains("zero") && it.translated.any{c->c in '\u4e00'..'\u9fff'}}
                assertNotNull("Completed Vosk words must retain their confidence",recorded.confidence)
                if(timing==SubtitleTiming.FAST) {
                    assertEquals("Actual subtitle check must run over a different player's foreground", "ai.opennomi.playbackfixture",device.currentPackageName)
                    assertFalse("Workspace Activity must be in background",ScreenState.ownForeground)
                    data class OverlaySnapshot(val text:String,val visible:Boolean,val bounds:android.graphics.Rect)
                    fun overlaySnapshot():OverlaySnapshot? {
                        var snapshot:OverlaySnapshot?=null
                        InstrumentationRegistry.getInstrumentation().runOnMainSync{
                            val service=ScreenShareService.instance ?: return@runOnMainSync
                            val overlay=ScreenShareService::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(service) ?: return@runOnMainSync
                            val view=OrbitOverlay::class.java.getDeclaredField("caption").apply{isAccessible=true}.get(overlay) as? android.widget.TextView ?: return@runOnMainSync
                            val location=IntArray(2);view.getLocationOnScreen(location)
                            val bounds=android.graphics.Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)
                            snapshot=OverlaySnapshot(view.text.toString(),view.isAttachedToWindow && view.isShown && view.windowToken!=null && view.width>0 && view.height>0,bounds)
                        }
                        return snapshot
                    }
                    suspend fun backgroundCaption():OverlaySnapshot {
                        var found:OverlaySnapshot?=null
                        withTimeout(10000){while(found==null){
                            val view=overlaySnapshot()
                            if(view?.visible==true && view.text.any{it in '\u4e00'..'\u9fff'} && view.text.contains("原文："))found=view
                            else delay(100)
                        }}
                        return checkNotNull(found)
                    }
                    val floating=backgroundCaption()
                    assertTrue("Floating window must contain actual translated player speech",floating.text.contains(recorded.translated))
                    assertTrue("Actual overlay must be registered with the system window manager",device.executeShellCommand("dumpsys window windows").contains("OpenNomi Background Subtitles"))
                    assertTrue("Subtitle view must lie within the current screen",floating.bounds.top>=0 && floating.bounds.bottom<=device.displayHeight)
                    // Tap through the real subtitle window to the other UID's full-screen click target.
                    device.click(floating.bounds.centerX(),floating.bounds.top+minOf(30,floating.bounds.height()/2))
                    assertTrue("Pass-through subtitle must not block the underlying player",device.wait(Until.hasObject(By.textContains("taps=1")),10000))
                    val notifications=app.getSystemService(android.app.NotificationManager::class.java)
                    fun currentNotice()=notifications.activeNotifications.first{it.id==402}.notification
                    val notice=currentNotice()
                    assertEquals(android.app.Notification.VISIBILITY_PRIVATE,notice.visibility)
                    assertNotNull(notice.publicVersion)
                    assertFalse("Lockscreen public text must not contain translated speech",notice.publicVersion.extras.getString(android.app.Notification.EXTRA_TEXT).orEmpty().contains(recorded.translated))
                    InstrumentationRegistry.getInstrumentation().runOnMainSync{cfg.subtitleOverlay=false;ScreenShareService.instance!!.refreshOverlay()}
                    withTimeout(10000){while(overlaySnapshot()!=null)delay(100)}
                    assertNull("Hide must remove only the actual visual window",overlaySnapshot())
                    assertTrue(ScreenState.state.value.audio)
                    notice.actions.first{it.title.toString()=="显示字幕"}.actionIntent.send()
                    backgroundCaption();assertEquals("ai.opennomi.playbackfixture",device.currentPackageName)
                    val approvedSession=ScreenState.state.value.session
                    currentNotice().actions.first{it.title.toString()=="暂停字幕"}.actionIntent.send()
                    withTimeout(10000){while(ScreenState.state.value.audio)delay(100)}
                    assertFalse(ScreenState.state.value.translation)
                    withTimeout(10000){while(overlaySnapshot()?.text?.contains("视频字幕已暂停")!=true)delay(100)}
                    withTimeout(10000){while(currentNotice().actions.none{it.title.toString()=="继续字幕"})delay(100)}
                    currentNotice().actions.first{it.title.toString()=="继续字幕"}.actionIntent.send()
                    withTimeout(60000){while(!ScreenState.state.value.audio || !ScreenState.state.value.captionOriginal.contains("zero") || ScreenState.state.value.caption.isBlank())delay(100)}
                    assertEquals("Notification resume must reuse the already approved capture session",approvedSession,ScreenState.state.value.session)
                    backgroundCaption();assertEquals("ai.opennomi.playbackfixture",device.currentPackageName)
                    val positionMenu=device.wait(Until.findObject(By.desc("OpenNomi 悬浮菜单")),10000)
                    assertNotNull(positionMenu);positionMenu.click()
                    val top=device.wait(Until.findObject(By.text("字幕位置：顶部")),10000)
                    assertNotNull(top);top.click()
                    withTimeout(10000){while(backgroundCaption().bounds.top>=device.displayHeight/2)delay(100)}
                    assertEquals(SubtitlePosition.TOP.name,cfg.subtitlePosition)
                    // Draw the actual secure overlay view while the separate player stays foreground.
                    val overlayFile=java.io.File(app.getExternalFilesDir(null),"background-subtitles.png")
                    InstrumentationRegistry.getInstrumentation().runOnMainSync{
                        val service=ScreenShareService.instance!!
                        val overlay=ScreenShareService::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(service)
                        val view=OrbitOverlay::class.java.getDeclaredField("caption").apply{isAccessible=true}.get(overlay) as android.widget.TextView
                        val lp=OrbitOverlay::class.java.getDeclaredField("captionParams").apply{isAccessible=true}.get(overlay) as android.view.WindowManager.LayoutParams
                        assertTrue("Pass-through subtitles must respect Android's overlay opacity limit",lp.alpha<=.8f)
                        assertTrue(lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE!=0)
                        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                        try{view.draw(android.graphics.Canvas(bitmap));java.io.FileOutputStream(overlayFile).use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
                    }
                    device.executeShellCommand("cp ${overlayFile.absolutePath} /data/local/tmp/background-subtitles.png")
                    assertEquals("Overlay and its controls must not bring OpenNomi to foreground","ai.opennomi.playbackfixture",device.currentPackageName)
                    // Exercise the real overlay entry while the external player owns the foreground.
                    val orb=device.wait(Until.findObject(By.desc("OpenNomi 悬浮菜单")),10000)
                    assertNotNull("Capture must expose its accessible floating menu",orb);orb.click()
                    val recent=device.wait(Until.findObject(By.text("查看近期视频译文")),10000)
                    assertNotNull("The overlay must offer recent captured translations",recent);recent.click()
                    assertTrue("Recent entry must bring the workspace forward",device.wait(Until.hasObject(By.pkg(app.packageName)),10000))
                    withTimeout(15000){while(!device.hasObject(By.text("本次共享 · 近期视频译文"))){
                        try{device.findObjects(By.scrollable(true)).firstOrNull{it.visibleBounds.height()>it.visibleBounds.width()}?.scroll(Direction.DOWN,.3f)}catch(_:StaleObjectException){}
                        delay(200)
                    }}
                    assertNotNull("Recent entry must switch a reused workspace to its history page",device.findObject(By.text("本次共享 · 近期视频译文")))
                    withTimeout(15000){while(!device.hasObject(By.text(recorded.translated))){
                        try{device.findObjects(By.scrollable(true)).firstOrNull{it.visibleBounds.height()>it.visibleBounds.width()}?.scroll(Direction.DOWN,.4f)}catch(_:StaleObjectException){}
                        delay(200)
                    }}
                    assertNotNull("Recent view must show the actual captured Chinese translation",device.findObject(By.text(recorded.translated)))
                    withTimeout(15000){while(!device.hasObject(By.textContains("zero"))){
                        device.swipe(device.displayWidth/20,device.displayHeight*3/4,device.displayWidth/20,device.displayHeight/3,25)
                        delay(200)
                    }}
                    assertNotNull(device.findObject(By.textContains("zero")))
                    scenario.onActivity{activity->activity.startActivity(Intent(activity,WorkspaceActivity::class.java).putExtra("tab","translation").putExtra("translation-mode","video").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
                    withTimeout(15000){
                        var clicked=false
                        while(!clicked) {
                            try {
                                val sync=device.findObject(By.text("重新同步当前声音"))
                                if(sync!=null && sync.visibleBounds.height()>0){sync.click();clicked=true}
                                else device.findObjects(By.scrollable(true)).firstOrNull{it.visibleBounds.height()>it.visibleBounds.width()}?.scroll(Direction.DOWN,.4f)
                            }catch(_:StaleObjectException){}
                            if(!clicked)delay(200)
                        }
                    }
                    assertTrue("Manual resync must preserve completed recent translations",ScreenState.state.value.captionHistory.contains(recorded))
                    withTimeout(60000){while(!ScreenState.state.value.status.contains("已就绪"))delay(100)}
                    withTimeout(60000){while(!ScreenState.state.value.captionOriginal.contains("zero") || !ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})delay(200)}
                    delay(1500);assertTrue("A late stop callback from the old capturer must not stop the resynced one",ScreenState.state.value.audio)
                    // Actual live video controls precede the collapsed model settings.
                    val output=java.io.File(app.getExternalFilesDir(null),"video-live-workspace.png")
                    scenario.onActivity{activity->
                        val view=activity.window.decorView
                        val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                        try{view.draw(android.graphics.Canvas(bitmap));java.io.FileOutputStream(output).use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
                    }
                    device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/video-live-workspace.png")
                    val sharingSession=ScreenState.state.value.session
                    scenario.onActivity{activity->activity.startActivity(Intent(activity,WorkspaceActivity::class.java).putExtra("tab","translation").putExtra("translation-mode","history").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
                    clickText(device,"暂停视频字幕，回看译文")
                    withTimeout(10000){while(ScreenState.state.value.audio)delay(100)}
                    assertFalse("Pause must not enable OCR translation implicitly",ScreenState.state.value.translation)
                    assertFalse(ScreenState.state.value.audio);assertTrue(ScreenState.state.value.captionHistory.contains(recorded))
                    delay(1500);assertEquals("",ScreenState.state.value.captionDraft);assertEquals("Paused captions must not be replaced by OCR or late audio results","",ScreenState.state.value.caption)
                    clickText(device,"继续视频字幕")
                    withTimeout(10000){while(!ScreenState.state.value.audio)delay(100)}
                    assertEquals("Resume must reuse the approved screen sharing session",sharingSession,ScreenState.state.value.session)
                    withTimeout(60000){while(!ScreenState.state.value.audio || !ScreenState.state.value.status.contains("已就绪"))delay(100)}
                    withTimeout(60000){while(!ScreenState.state.value.captionOriginal.contains("zero") || !ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})delay(200)}
                    assertTrue("History resume must produce actual Chinese from the player again",ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance!!.toggleAudio()}
                assertFalse(ScreenState.state.value.audio)
                assertFalse("Pausing video must stop translation instead of falling back to OCR",ScreenState.state.value.translation)
                assertFalse("Pausing subtitles must retain this sharing session's recent text",ScreenState.state.value.captionHistory.isEmpty())
                delay(1500);assertFalse("Old capture callbacks must not restart the cancelled session",ScreenState.state.value.audio)
            }
        } finally {
            val service=ScreenShareService.instance
            // Hold the capture worker busy, as a native pixel copy can be during stop.
            // The reader surface must survive until queued capture work has returned.
            val handler=service?.let{ScreenShareService::class.java.getDeclaredField("handler").apply{isAccessible=true}.get(it) as? android.os.Handler}
            val reader=service?.let{ScreenShareService::class.java.getDeclaredField("reader").apply{isAccessible=true}.get(it) as? android.media.ImageReader}
            val surface=reader?.surface
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            try {
                if(handler!=null && surface!=null) {
                    assertTrue(handler.post{entered.countDown();release.await(20,java.util.concurrent.TimeUnit.SECONDS)})
                    assertTrue("Capture worker must reach the blocked operation",withContext(Dispatchers.IO){entered.await(15,java.util.concurrent.TimeUnit.SECONDS)})
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync{service?.stopSelf()}
                withTimeout(10000){while(ScreenState.state.value.active)delay(100)}
                if(handler!=null && surface!=null)assertTrue("Stopping must not invalidate a reader while capture work is running",surface.isValid)
            } finally {release.countDown()}
            if(surface!=null)withTimeout(15000){while(surface.isValid)delay(100)}
            device.executeShellCommand("am force-stop ai.opennomi.playbackfixture")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            cfg.playbackModel=old;cfg.phoneTranslation=phone;cfg.translationCellular=cellular;cfg.subtitleTiming=oldTiming
            cfg.subtitleOverlay=oldOverlay;cfg.subtitlePosition=oldPosition;cfg.subtitleOriginal=oldOriginal;cfg.subtitleLarge=oldLarge
            withTimeout(10000){while(ScreenState.state.value.active)delay(100)}
            assertTrue("Stopping sharing must clear recent captured text",ScreenState.state.value.captionHistory.isEmpty())
            assertEquals("",ScreenState.state.value.captionDraft);assertEquals("",ScreenState.state.value.captionDraftOriginal)
        }
    }
    private suspend fun clickText(device:UiDevice,label:String) {
        withTimeout(15000) {
            var clicked=false
            while(!clicked) {
                try {
                    val button=device.findObject(By.text(label))
                    if(button!=null && button.visibleBounds.height()>0){button.click();clicked=true}
                    else device.findObjects(By.scrollable(true)).firstOrNull{it.visibleBounds.height()>it.visibleBounds.width()}?.scroll(Direction.DOWN,.3f)
                }catch(_:StaleObjectException){}
                if(!clicked)delay(200)
            }
        }
    }
    private suspend fun clickVideoStart(device:UiDevice) {
        withTimeout(15000) {
            var clicked=false
            while(!clicked) {
                try {
                    val button=device.findObject(By.text("开启无字幕视频字幕"))
                    if(button!=null && button.visibleBounds.height()>0){button.click();clicked=true}
                    else {
                val scroll=device.findObjects(By.scrollable(true)).firstOrNull{node->val bounds=node.visibleBounds;bounds.height()>bounds.width()}
                if(scroll!=null)scroll.scroll(Direction.DOWN,.65f)
                else device.swipe(device.displayWidth/10,device.displayHeight*3/4,device.displayWidth/10,device.displayHeight/3,25)
                    }
                } catch(_:StaleObjectException) {
                    // Compose may replace accessibility nodes during progress updates.
                    // Reacquire the button on the next iteration; the timeout still fails a missing control.
                }
                if(!clicked)delay(200)
            }
        }
    }

    @Test fun realJapaneseModelInstallsAndSurvivesOfflineReload()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val spec=PlaybackModels.selected("ja")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val wifi=device.executeShellCommand("settings get global wifi_on").trim()
        val data=device.executeShellCommand("settings get global mobile_data").trim()
        try {
            val path=withTimeout(480000){withContext(Dispatchers.IO){PlaybackModels.prepare(app,spec,true)}}
            assertTrue(PlaybackModels.ready(app,spec))
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("ja",true)}
            device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
            withContext(Dispatchers.IO){
                val offline=PlaybackModels.prepare(app,spec,false)
                assertEquals(path,offline)
                org.vosk.Model(offline.absolutePath).use{model->org.vosk.Recognizer(model,16000f).use{recognizer->
                    assertFalse(recognizer.acceptWaveForm(ByteArray(3200),3200))
                    assertTrue(org.json.JSONObject(recognizer.finalResult).optString("text").isBlank())
                }}
            }
            val translated=ScreenAssistant.phoneTranslator.translate("今日はいい天気です。","ja")
            assertTrue(translated.any{it in '\u4e00'..'\u9fff'});assertFalse(translated.contains("です"))
        } finally {
            device.executeShellCommand("svc wifi ${if(wifi=="0")"disable" else "enable"}")
            device.executeShellCommand("svc data ${if(data=="0")"disable" else "enable"}")
        }
    }
}
