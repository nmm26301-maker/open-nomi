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
            assertEquals("Pausing capture must retain the route",AudioManager.MODE_IN_COMMUNICATION,manager.mode)
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(9600))))
            withTimeout(15000){fish.speak("耳机对话测试",FishAudioConfig("test",base=server.url("/").toString()),duplex=false)}
            assertTrue(playback.isCompleted)
            assertTrue("Half duplex must retain communication routing",routes.active)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION,manager.mode)
            withContext(Dispatchers.Main.immediate){first!!.close();first!!.close()}
            assertTrue(routes.active)
            withContext(Dispatchers.Main.immediate){second!!.close()}
            assertFalse(routes.active);assertEquals(oldMode,manager.mode)
        } finally {
            withContext(Dispatchers.Main.immediate){engine.release();fish.release();first?.close();second?.close()}
            manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,oldVolume,0);server.shutdown()
        }
    }
}
