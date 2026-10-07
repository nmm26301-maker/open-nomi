package ai.opennomi.app

import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.opennomi.app.voice.*
import ai.opennomi.app.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FishStreamingSmokeTest {
    @Test fun realAndroidTrackStartsBeforeTheNetworkBodyCompletes() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val audio=context.getSystemService(AudioManager::class.java);val previous=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val server=MockWebServer();server.start()
        val playing=CompletableDeferred<Int>()
        val speaker=FishSpeech(context,onPlayback={if(it>0)playing.complete(it)})
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            server.enqueue(MockResponse().setHeader("Content-Type","application/octet-stream")
                .setBody(Buffer().write(ByteArray(48000))).throttleBody(2400,100,TimeUnit.MILLISECONDS))
            val job=launch {speaker.speak("你好",FishAudioConfig("test",base=server.url("/").toString()))}
            assertTrue(withTimeout(15000){playing.await()}>0)
            assertTrue("Audio must start while the body is still downloading",job.isActive)
            withTimeout(15000){job.join()};assertFalse(job.isCancelled)
        } finally {withContext(Dispatchers.Main.immediate){speaker.release()};audio.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0);server.shutdown()}
    }
    @Test fun stopCancelsTheNetworkBodyAndAllowsAnotherStreamingReply() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val audio=context.getSystemService(AudioManager::class.java);val previous=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val server=MockWebServer();server.start()
        val playing=CompletableDeferred<Unit>();val speaker=FishSpeech(context,onPlayback={if(it>0)playing.complete(Unit)})
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(240000))).throttleBody(2400,100,TimeUnit.MILLISECONDS))
            val first=launch {speaker.speak("第一段",FishAudioConfig("test",base=server.url("/").toString()))}
            withTimeout(15000){playing.await()}
            withContext(Dispatchers.Main.immediate){speaker.stop()}
            withTimeout(5000){first.join()};assertTrue(first.isCancelled)
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(9600))))
            withTimeout(15000){speaker.speak("第二段",FishAudioConfig("test",base=server.url("/").toString()))}
        } finally {withContext(Dispatchers.Main.immediate){speaker.release()};audio.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0);server.shutdown()}
    }
    @Test fun alternateXiaozhiProfileAndFishSpeakTheFirstSentenceBeforeTtsStop() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val audio=context.getSystemService(AudioManager::class.java);val previous=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val fish=MockWebServer();fish.start();val xiaozhi=MockWebServer();xiaozhi.start()
        val model=withContext(Dispatchers.Main.immediate){OpenNomiCloudViewModel(context)}
        val oldFish=model.fishSettings.connection();val oldFishEnabled=model.fishSettings.enabled
        val oldEndpoint=model.endpointSettings.connection();val oldEndpointEnabled=model.endpointSettings.enabled
        val oldPhone=model.voiceSettings.phoneControl
        val socket=CompletableDeferred<WebSocket>()
        xiaozhi.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onMessage(ws:WebSocket,text:String) {
                val event=JSONObject(text)
                if(event.optString("type")=="hello") {
                    ws.send("{\"type\":\"hello\",\"session_id\":\"test-session\",\"transport\":\"websocket\",\"audio_params\":{\"format\":\"opus\",\"sample_rate\":16000,\"channels\":1}}")
                    socket.complete(ws)
                }
                if(event.optString("type")=="listen" && event.optString("state")=="detect") {
                    ws.send("{\"type\":\"llm\",\"text\":\"第一句。\"}")
                }
            }
        }))
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,3,0)
            model.voiceSettings.phoneControl=false
            model.fishSettings.save(FishAudioConfig("test",base=fish.url("/").toString()),true)
            model.endpointSettings.save(VoiceEndpoint(xiaozhi.url("/xiaozhi/v1/").toString().replaceFirst("http://","ws://"),"test-token",1),true)
            fish.enqueue(MockResponse().setBody(Buffer().write(ByteArray(12000))).throttleBody(2400,100,TimeUnit.MILLISECONDS))
            fish.enqueue(MockResponse().setBody(Buffer().write(ByteArray(9600))))
            withContext(Dispatchers.Main.immediate){model.connect()}
            withTimeout(15000){model.connected.first{it}}
            withContext(Dispatchers.Main.immediate){assertTrue(model.askScreenText("测试回答"))}
            withTimeout(15000){model.status.first{it.startsWith("正在播放")}}
            assertEquals("第一句。",model.response.value)
            val ws=withTimeout(1000){socket.await()}
            ws.send("{\"type\":\"llm\",\"text\":\"第二句。\"}")
            ws.send("{\"type\":\"tts\",\"state\":\"stop\"}")
            val first=withContext(Dispatchers.IO){fish.takeRequest(10,TimeUnit.SECONDS)}
            val second=withContext(Dispatchers.IO){fish.takeRequest(10,TimeUnit.SECONDS)}
            assertEquals("第一句。",JSONObject(first!!.body.readUtf8()).getString("text"))
            assertEquals("第二句。",JSONObject(second!!.body.readUtf8()).getString("text"))
            val handshake=withContext(Dispatchers.IO){xiaozhi.takeRequest(5,TimeUnit.SECONDS)}
            assertEquals("Bearer test-token",handshake!!.getHeader("Authorization"))
            assertEquals("1",handshake.getHeader("Protocol-Version"))
        } finally {
            withContext(Dispatchers.Main.immediate){model.disconnect()}
            model.fishSettings.save(oldFish,oldFishEnabled && oldFish.apiKey.isNotBlank())
            model.endpointSettings.save(oldEndpoint,oldEndpointEnabled);model.voiceSettings.phoneControl=oldPhone
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,previous,0)
            fish.shutdown();xiaozhi.shutdown()
        }
    }
}
