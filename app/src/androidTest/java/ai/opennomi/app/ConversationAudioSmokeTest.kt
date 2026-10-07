package ai.opennomi.app

import android.media.AudioManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.opennomi.app.audio.*
import ai.opennomi.app.voice.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConversationAudioSmokeTest {
    @Test fun routeLeaseSurvivesHalfDuplexFishAndRestoresModeAfterLastOwner() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val manager=context.getSystemService(AudioManager::class.java)
        val routes=context.audioRoutes;val oldMode=manager.mode
        val oldVolume=manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val server=MockWebServer();server.start()
        val playback=CompletableDeferred<Unit>()
        val fish=FishSpeech(context,onPlayback={if(it>0)playback.complete(Unit)})
        val engine=RealtimeAudioEngine(context,{}, {}, onError={throw AssertionError(it)})
        var first:ConversationAudioRoutes.Lease?=null;var second:ConversationAudioRoutes.Lease?=null
        try {
            manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,3,0)
            withContext(Dispatchers.Main.immediate){first=routes.acquire();second=routes.acquire();routes.awaitReady()}
            assertTrue(routes.active);assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.mode)
            if(Build.VERSION.SDK_INT>=31)assertEquals(routes.state.value.device!!.id,manager.communicationDevice!!.id)
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.RECORD_AUDIO").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            withContext(Dispatchers.Main.immediate){assertTrue(engine.startRecording(encodeCapture=false))}
            assertTrue(engine.isRecording())
            withContext(Dispatchers.Main.immediate){engine.stopRecording()}
            assertTrue("Pausing capture must retain our conversation lease",routes.active)
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(48000))).throttleBody(2400,100,TimeUnit.MILLISECONDS))
            val reply=launch { fish.speak("耳机对话测试",FishAudioConfig("test",base=server.url("/").toString()),duplex=false) }
            withTimeout(15000){playback.await()}
            withTimeout(5000){while(manager.mode!=AudioManager.MODE_IN_COMMUNICATION)delay(20)}
            assertTrue("Validate routing during actual half-duplex playback",reply.isActive)
            if(Build.VERSION.SDK_INT>=31)assertEquals(routes.state.value.device!!.id,manager.communicationDevice!!.id)
            withTimeout(15000){reply.join()}
            assertTrue("Half duplex must retain communication routing",routes.active)
            // Android 12+ may temporarily return to NORMAL while no audio runs;
            // our requested route and mode ownership remain until the last lease closes.
            withContext(Dispatchers.Main.immediate){first!!.close();first!!.close()}
            assertTrue(routes.active)
            withContext(Dispatchers.Main.immediate){second!!.close()}
            withTimeout(3000){while(manager.mode!=oldMode)delay(20)}
            assertFalse(routes.active);assertEquals(oldMode,manager.mode)
        } finally {
            withContext(Dispatchers.Main.immediate){engine.release();fish.release();first?.close();second?.close()}
            manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,oldVolume,0);server.shutdown()
        }
    }
}
