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
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio=app.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            cfg.playbackModel="en";cfg.phoneTranslation=true;cfg.translationCellular=true
            cfg.subtitleTiming=timing.name
            withContext(Dispatchers.IO){PlaybackModels.prepare(app,PlaybackModels.selected("en"),true)}
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("en",true)}
            device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            if(android.os.Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0)
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation")).use {
                clickVideoStart(device)
                val grant=device.wait(Until.findObject(By.res("android:id/button1")),10000)
                assertNotNull("MediaProjection permission dialog must appear",grant);grant.click()
                withTimeout(60000){while(!ScreenState.state.value.audio || !ScreenState.state.value.status.contains("已就绪"))delay(200)}
                device.executeShellCommand("am start -n ai.opennomi.playbackfixture/.PlaybackFixtureActivity")
                withTimeout(60000){while(!ScreenState.state.value.captionOriginal.contains("zero") || !ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})delay(200)}
                assertTrue(ScreenState.state.value.audio)
                assertTrue(ScreenState.state.value.captionOriginal.contains("zero"))
                assertTrue(ScreenState.state.value.caption.any{it in '\u4e00'..'\u9fff'})
                InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance!!.toggleAudio()}
                assertFalse(ScreenState.state.value.audio)
                delay(1500);assertFalse("Old capture callbacks must not restart the cancelled session",ScreenState.state.value.audio)
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync{ScreenShareService.instance?.stopSelf()}
            device.executeShellCommand("am force-stop ai.opennomi.playbackfixture")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0)
            cfg.playbackModel=old;cfg.phoneTranslation=phone;cfg.translationCellular=cellular;cfg.subtitleTiming=oldTiming
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
