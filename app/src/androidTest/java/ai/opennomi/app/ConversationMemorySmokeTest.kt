package ai.opennomi.app

import android.content.Intent
import android.media.AudioManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import ai.opennomi.app.voice.*
import ai.opennomi.app.network.VoiceEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ConversationMemorySmokeTest {
    @Test fun androidPrivateStorageRestoresCompletedChatsAndClearAcrossInstances() {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val key="memory-smoke-test"
        val prefs=context.getSharedPreferences("open_nomi_conversation_memory",0)
        prefs.edit().remove(key).commit()
        try {
            val first=LocalConversationMemory(context,key).conversation
            assertTrue(first.record("我叫小林","记住了",0))
            assertTrue(first.pin(first.state.value.turns.single(),true))
            val reopened=LocalConversationMemory(context,key).conversation
            assertEquals("我叫小林",reopened.state.value.turns.single().user)
            assertTrue(reopened.state.value.turns.single().pinned)
            assertTrue(reopened.prompt("我叫什么名字").contains("小林"))
            reopened.clear()
            assertTrue(LocalConversationMemory(context,key).conversation.state.value.turns.isEmpty())
        } finally {prefs.edit().remove(key).commit()}
    }

    @Test fun rememberedContextReachesXiaozhiAndCompletedFishReplyIsSaved() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val memory=ConversationMemory();memory.record("我叫小林","很高兴认识你",0)
        val model=withContext(Dispatchers.Main.immediate){OpenNomiCloudViewModel(context,memory)}
        val manager=context.getSystemService(AudioManager::class.java)
        val streams=listOf(AudioManager.STREAM_MUSIC,AudioManager.STREAM_VOICE_CALL)
        val volumes=streams.associateWith{manager.getStreamVolume(it)}
        val oldFish=model.fishSettings.connection();val fishEnabled=model.fishSettings.enabled
        val oldEndpoint=model.endpointSettings.connection();val endpointEnabled=model.endpointSettings.enabled
        val oldPhone=model.voiceSettings.phoneControl
        val fish=MockWebServer();fish.start();val server=MockWebServer();server.start()
        val prompt=CompletableDeferred<String>();val socket=CompletableDeferred<WebSocket>()
        val closed=CompletableDeferred<Unit>();val recognized=AtomicBoolean(false)
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
            override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
            override fun onMessage(ws:WebSocket,text:String){
                val event=JSONObject(text)
                if(event.optString("type")=="hello") {
                    socket.complete(ws)
                    ws.send("""{"type":"hello","session_id":"memory-test"}""")
                }
                if(event.optString("type")=="listen" && event.optString("state")=="start" && recognized.compareAndSet(false,true))
                    ws.send("""{"type":"stt","text":"我叫什么名字"}""")
                if(event.optString("type")=="listen" && event.optString("state")=="detect") {
                    prompt.complete(event.getString("text"))
                    // A stop from the aborted automatic response cannot finish the new request.
                    ws.send("""{"type":"tts","state":"stop"}""")
                    ws.send("""{"type":"llm","text":"你叫小林。"}""")
                    ws.send("""{"type":"tts","state":"stop"}""")
                }
            }
        }))
        try {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            streams.forEach{manager.setStreamVolume(it,3,0)}
            model.voiceSettings.phoneControl=false
            model.fishSettings.save(FishAudioConfig("test",base=fish.url("/").toString()),true)
            model.endpointSettings.save(VoiceEndpoint(server.url("/").toString().replaceFirst("http://","ws://")),true)
            fish.enqueue(MockResponse().setBody(Buffer().write(ByteArray(9600))))
            withContext(Dispatchers.Main.immediate){model.startBackgroundConversation(false)}
            val sent=withTimeout(15000){prompt.await()}
            assertTrue(sent.contains("我叫小林"));assertTrue(sent.endsWith("用户现在说：我叫什么名字"))
            val saved=withTimeout(20000){memory.state.first{it.turns.size==2}}
            assertEquals("我叫什么名字",saved.turns.last().user)
            assertEquals("你叫小林。",saved.turns.last().assistant)
            assertEquals(1,fish.requestCount)
        } finally {
            withContext(Dispatchers.Main.immediate){model.disconnect()}
            if(socket.isCompleted) {
                try {withTimeout(5000){closed.await()}}
                finally {if(!closed.isCompleted)socket.await().cancel()}
            }
            model.fishSettings.save(oldFish,fishEnabled && oldFish.apiKey.isNotBlank())
            model.endpointSettings.save(oldEndpoint,endpointEnabled);model.voiceSettings.phoneControl=oldPhone
            volumes.forEach{(stream,volume)->manager.setStreamVolume(stream,volume,0)}
            fish.shutdown();server.shutdown()
        }
    }

    @Test fun homeMemoryDialogShowsTurnsAndClearsThemWithConfirmation() {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val model=context.cloudModel;val memory=context.conversationMemory
        val oldPhone=model.voiceSettings.phoneControl
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model.disconnect();model.voiceSettings.phoneControl=true
            memory.clear();memory.enabled(true)
            memory.record("我叫小林","记住了，下次接着聊",memory.state.value.revision)
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {
                assertTrue(device.wait(Until.hasObject(By.text("记忆 1")),10000))
                device.findObject(By.text("记忆 1")).click()
                assertTrue(device.wait(Until.hasObject(By.text("本机对话记忆")),5000))
                assertNotNull(device.findObject(By.text("你：我叫小林")))
                assertNotNull(device.findObject(By.text("NOMI：记住了，下次接着聊")))
                device.findObject(By.text("长期记住")).click()
                assertTrue(device.wait(Until.hasObject(By.text("长期 1 / 4")),5000))
                device.findObject(By.text("长期 1 / 4")).click()
                assertNotNull(device.findObject(By.text("取消长期")))
                assertTrue(memory.state.value.turns.single().pinned)
                val output=File(context.getExternalFilesDir(null),"conversation-memory.png")
                assertTrue(device.takeScreenshot(output))
                device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/conversation-memory.png")
                device.findObject(By.text("清空记忆")).click()
                assertTrue(device.wait(Until.hasObject(By.text("确认清空")),5000))
                device.findObject(By.text("确认清空")).click()
                assertTrue(device.wait(Until.hasObject(By.text("还没有完成的聊天。聊完后会自动保存在这里。")),5000))
                assertTrue(memory.state.value.turns.isEmpty())
                device.findObject(By.text("完成")).click()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync{model.disconnect();memory.clear();memory.enabled(true);model.voiceSettings.phoneControl=oldPhone}
        }
    }
}
