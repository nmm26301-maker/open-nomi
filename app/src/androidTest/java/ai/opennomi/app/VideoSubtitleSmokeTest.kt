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
    @Test fun realCapturedPlayerAudioProducesChineseWithoutAnyScreenSubtitle()=runBlocking<Unit> {
        val app=ApplicationProvider.getApplicationContext<NomiApplication>()
        val cfg=ScreenAssistant.settings();val old=cfg.playbackModel;val phone=cfg.phoneTranslation;val cellular=cfg.translationCellular
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val audio=app.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            cfg.playbackModel="en";cfg.phoneTranslation=true;cfg.translationCellular=true
            withContext(Dispatchers.IO){PlaybackModels.prepare(app,PlaybackModels.selected("en"),true)}
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("en",true)}
            device.executeShellCommand("pm grant ${app.packageName} android.permission.RECORD_AUDIO")
            if(android.os.Build.VERSION.SDK_INT>=33)device.executeShellCommand("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            device.executeShellCommand("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),0)
            ActivityScenario.launch<WorkspaceActivity>(Intent(app,WorkspaceActivity::class.java).putExtra("tab","translation")).use {
                withTimeout(15000){while(!device.hasObject(By.text("开启无字幕视频字幕"))){device.findObjects(By.scrollable(true)).firstOrNull{it.visibleBounds.height()>it.visibleBounds.width()}?.scroll(Direction.DOWN,.5f);delay(200)}}
                device.findObject(By.text("开启无字幕视频字幕")).click()
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
            cfg.playbackModel=old;cfg.phoneTranslation=phone;cfg.translationCellular=cellular
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
