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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HyMtVideoSmokeTest {
    @Test fun capturedNaturalSpeechUsesHyMtAndStreamsChineseOverTheBackgroundPlayer()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val cfg=ScreenAssistant.settings()
        val old=listOf(cfg.phoneTranslation.toString(),cfg.hyMtTranslation.toString(),cfg.translationCellular.toString(),cfg.playbackModel,cfg.subtitleTiming,cfg.subtitleOverlay.toString())
        val manager=app.getSystemService(AudioManager::class.java);val volume=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val report=JSONObject().put("model_sha256",HyMtModel.SHA256).put("fixture","Pinned public JFK recording; separate player UID")
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance?.stopSelf()}
            withTimeout(10000){while(ScreenState.state.value.active || ScreenShareService.instance!=null)delay(100)}
            cfg.phoneTranslation=true;cfg.hyMtTranslation=true;cfg.translationCellular=true;cfg.playbackModel="en";cfg.subtitleTiming=SubtitleTiming.FAST.name;cfg.subtitleOverlay=true
            withContext(Dispatchers.IO){PlaybackModels.prepare(app,PlaybackModels.selected("en"),true)}
            withTimeout(1200000){ScreenAssistant.prepareTranslation("en",true)}
            device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            if(android.os.Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0)
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation").putExtra("translation-mode","video")).use {
                report.put("ui_stage","start_button");withTimeout(20000){while(true){
                    val start=device.findObject(By.text("开启无字幕视频字幕"))
                    if(start!=null && start.visibleBounds.height()>0){start.click();break}
                    device.swipe(device.displayWidth/10,device.displayHeight*4/5,device.displayWidth/10,device.displayHeight/3,30);delay(150)
                }}
                report.put("before_grant_active",ScreenState.state.value.active).put("before_grant_audio",ScreenState.state.value.audio).put("before_grant_package",device.currentPackageName)
                report.put("ui_stage","projection_permission");val grant=device.wait(Until.findObject(By.res("android:id/button1")),10000);assertNotNull("Fresh capture must request projection permission; package=${device.currentPackageName}, active=${ScreenState.state.value.active}, audio=${ScreenState.state.value.audio}, status=${ScreenState.state.value.status}",grant);grant.click()
                report.put("ui_stage","capture_ready");withTimeout(180000){while(!ScreenState.state.value.audio || !ScreenState.state.value.status.contains("已就绪"))delay(100)}
                report.put("ui_stage","native_stream");val started=SystemClock.elapsedRealtime()
                device.executeShellCommand("am start -n ai.opennomi.playbackfixture/.PlaybackFixtureActivity --ez natural true")
                var first=-1L
                withTimeout(180000){while(ScreenState.state.value.captionHistory.isEmpty()) {
                    val state=ScreenState.state.value
                    if(first<0 && state.captionDraft.any{it in '\u4e00'..'\u9fff'}) {
                        assertTrue(state.captionDraftLabel.contains("Hy-MT2"));first=SystemClock.elapsedRealtime()-started
                    }
                    delay(25)
                }}
                val finalized=SystemClock.elapsedRealtime()-started
                assertTrue("Native Hy-MT2 stream must appear before completed caption",first>=0 && first<finalized)
                assertEquals("ai.opennomi.playbackfixture",device.currentPackageName);assertFalse(ScreenState.ownForeground)
                var rendered=""
                withTimeout(15000){while(rendered.isEmpty()) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val service=ScreenShareService.instance!!
                        val overlay=ScreenShareService::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(service)
                        val view=OrbitOverlay::class.java.getDeclaredField("caption").apply{isAccessible=true}.get(overlay) as? android.widget.TextView
                        if(view?.isShown==true && view.isAttachedToWindow && view.width>0 && view.height>0 && view.text.contains("Hy-MT2") &&
                            ScreenState.state.value.captionHistory.any{view.text.contains(it.translated) && it.translated.any{c->c in '\u4e00'..'\u9fff'}}) {
                            rendered=view.text.toString()
                            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                            try{view.draw(android.graphics.Canvas(bitmap));File(app.getExternalFilesDir(null),"hymt-background.png").outputStream().use{assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
                        }
                    }
                    if(rendered.isEmpty())delay(50)
                }}
                report.put("first_stream_elapsed_ms",first).put("first_final_elapsed_ms",finalized).put("overlay",rendered)
                    .put("captured_original",ScreenState.state.value.captionHistory.first().original).put("translated",ScreenState.state.value.captionHistory.first().translated)
                device.executeShellCommand("cp ${File(app.getExternalFilesDir(null),"hymt-background.png").absolutePath} /data/local/tmp/hymt-background.png")
                InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance!!.toggleAudio()}
                assertFalse(ScreenState.state.value.audio);assertEquals("",ScreenState.state.value.captionDraft)
                delay(1200);assertEquals("",ScreenState.state.value.captionDraft);assertTrue(ScreenState.state.value.captionHistory.isNotEmpty())
                val approved=ScreenState.state.value.session;val count=ScreenState.state.value.captionHistory.size
                InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance!!.toggleAudio()}
                withTimeout(180000){while(ScreenState.state.value.captionHistory.size<=count)delay(100)}
                assertEquals(approved,ScreenState.state.value.session);report.put("resume_same_session",true)
            }
        }finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance?.stopSelf()}
            withTimeout(10000){while(ScreenState.state.value.active)delay(100)}
            delay(500);assertEquals("",ScreenState.state.value.captionDraft);assertTrue(ScreenState.state.value.captionHistory.isEmpty())
            device.executeShellCommand("am force-stop ai.opennomi.playbackfixture")
            manager.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            cfg.phoneTranslation=old[0].toBoolean();cfg.hyMtTranslation=old[1].toBoolean();cfg.translationCellular=old[2].toBoolean();cfg.playbackModel=old[3];cfg.subtitleTiming=old[4];cfg.subtitleOverlay=old[5].toBoolean()
            val output=File(app.getExternalFilesDir(null),"hymt-video.json");output.writeText(report.toString(2));device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/hymt-video.json")
        }
    }
}
