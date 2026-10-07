package ai.opennomi.app

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opennomi.app.voice.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class FishPlaybackSmokeTest {
    private fun wave(milliseconds:Int):ByteArray {
        val rate=16000;val samples=rate*milliseconds/1000;val bytes=samples*2
        val b=ByteBuffer.allocate(44+bytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray());b.putInt(36+bytes);b.put("WAVEfmt ".toByteArray())
        b.putInt(16);b.putShort(1);b.putShort(1);b.putInt(rate);b.putInt(rate*2)
        b.putShort(2);b.putShort(16);b.put("data".toByteArray());b.putInt(bytes)
        repeat(samples){b.putShort((sin(2*Math.PI*440*it/rate)*2000).toInt().toShort())}
        return b.array()
    }
    @Test fun receivedAudioPreparesPlaysAndCompletesThroughTheRealAndroidPlayer() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val audio=context.getSystemService(AudioManager::class.java)
        val previous=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val server=MockWebServer();server.start()
        val stages=mutableListOf<String>();val sessions=mutableListOf<Int>()
        val speaker=FishSpeech(context,FishAudioApi(OkHttpClient()),onPlayback={sessions.add(it)},onStage={stages.add(it)})
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wave(500))))
            withTimeout(20000){speaker.speak("你好",FishAudioConfig("test-key",base=server.url("/").toString()))}
            assertTrue(stages.any{it.startsWith("请求 FishAudio")})
            assertTrue(stages.any{it.startsWith("已收到音频")})
            assertTrue(stages.any{it.startsWith("正在播放")})
            assertTrue(sessions.any{it>0});assertEquals(0,sessions.last())
        } finally {withContext(Dispatchers.Main.immediate){speaker.release()};audio.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0);server.shutdown()}
    }
    @Test fun stopCancelsPlaybackAndTheSameSpeakerCanPlayAgain() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val audio=context.getSystemService(AudioManager::class.java);val previous=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val server=MockWebServer();server.start()
        val playing=CompletableDeferred<Unit>()
        val speaker=FishSpeech(context,FishAudioApi(OkHttpClient()),onStage={if(it.startsWith("正在播放"))playing.complete(Unit)})
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wave(3000))))
            val first=launch {speaker.speak("第一段",FishAudioConfig("test-key",base=server.url("/").toString()))}
            withTimeout(15000){playing.await()}
            withContext(Dispatchers.Main.immediate){speaker.stop()}
            withTimeout(5000){first.join()};assertTrue(first.isCancelled)
            server.enqueue(MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wave(200))))
            withTimeout(15000){speaker.speak("重试",FishAudioConfig("test-key",base=server.url("/").toString()))}
        } finally {withContext(Dispatchers.Main.immediate){speaker.release()};audio.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0);server.shutdown()}
    }
}
