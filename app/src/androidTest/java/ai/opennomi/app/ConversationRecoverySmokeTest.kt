package ai.opennomi.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import ai.opennomi.app.voice.ConversationMemory
import ai.opennomi.app.model.ConversationState
import ai.opennomi.app.network.VoiceEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConversationRecoverySmokeTest {
    @Test fun foregroundContinuousChatReopensListeningAfterSocketLoss() = recovery(false)
    @Test fun manualPauseCancelsAQueuedForegroundReconnect() = recovery(true)
    private fun recovery(pause:Boolean) = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val model=withContext(Dispatchers.Main.immediate){OpenNomiCloudViewModel(context,ConversationMemory())}
        val endpoint=model.endpointSettings.connection();val enabled=model.endpointSettings.enabled
        val phone=model.voiceSettings.phoneControl;val continuous=model.voiceSettings.continuousConversation
        val server=MockWebServer();server.start()
        val opened=CopyOnWriteArrayList<Pair<WebSocket,CompletableDeferred<Unit>>>()
        val first=CompletableDeferred<WebSocket>();val next=CompletableDeferred<Unit>();val starts=AtomicInteger()
        repeat(2){index->
            val closed=CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onOpen(ws:WebSocket,response:Response){opened.add(ws to closed)}
                override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
                override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
                override fun onMessage(ws:WebSocket,text:String){
                    val event=JSONObject(text)
                    if(event.optString("type")=="hello")ws.send("""{"type":"hello","session_id":"recovery-$index"}""")
                    if(event.optString("type")=="listen" && event.optString("state")=="start") {
                        if(starts.incrementAndGet()==1)first.complete(ws) else next.complete(Unit)
                    }
                }
            }))
        }
        try {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            model.voiceSettings.phoneControl=false;model.voiceSettings.continuousConversation=true
            model.endpointSettings.save(VoiceEndpoint(server.url("/").toString().replaceFirst("http://","ws://")),true)
            withContext(Dispatchers.Main.immediate){model.connect()}
            withTimeout(15000){model.connected.first{it}}
            withContext(Dispatchers.Main.immediate){model.toggleListening()}
            val ws=withTimeout(15000){first.await()}
            assertFalse(model.backgroundConversation.value)
            ws.close(1012,"test server restart")
            withTimeout(10000){model.connected.first{!it}}
            if(pause) {
                withContext(Dispatchers.Main.immediate){model.pauseConversation()}
                delay(3000)
                assertEquals(1,server.requestCount);assertFalse(next.isCompleted)
                assertEquals(ConversationState.IDLE,model.state.value)
            } else {
                withTimeout(15000){next.await()}
                assertTrue(model.connected.value);assertFalse(model.backgroundConversation.value)
                assertEquals(ConversationState.LISTENING,model.state.value)
                assertTrue(model.audioRoute.value.active);assertEquals(2,server.requestCount)
            }
        } finally {
            withContext(Dispatchers.Main.immediate){model.disconnect()}
            for((ws,closed) in opened)if(withTimeoutOrNull(5000){closed.await()}==null)ws.cancel()
            model.endpointSettings.save(endpoint,enabled)
            model.voiceSettings.phoneControl=phone;model.voiceSettings.continuousConversation=continuous
            server.shutdown()
        }
    }
}
