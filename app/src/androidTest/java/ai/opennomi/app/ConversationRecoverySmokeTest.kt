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
    @Test fun cancelledStartStaysSilentAfterLateHelloAndSocketLoss() = pendingStart(false)
    @Test fun userCanRequestStartAgainAfterCancellingBeforeHello() = pendingStart(true)
    private fun pendingStart(startAgain:Boolean) = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val model=withContext(Dispatchers.Main.immediate){OpenNomiCloudViewModel(context,ConversationMemory())}
        val endpoint=model.endpointSettings.connection();val enabled=model.endpointSettings.enabled
        val phone=model.voiceSettings.phoneControl;val continuous=model.voiceSettings.continuousConversation
        val realtime=model.voiceSettings.realtimeConversation
        val server=MockWebServer();server.start()
        val hello=CompletableDeferred<WebSocket>();val started=CompletableDeferred<Unit>();val closed=CompletableDeferred<Unit>()
        val starts=AtomicInteger()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
            override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
            override fun onMessage(ws:WebSocket,text:String){
                val event=JSONObject(text)
                if(event.optString("type")=="hello")hello.complete(ws)
                if(event.optString("type")=="listen" && event.optString("state")=="start") {
                    starts.incrementAndGet();started.complete(Unit)
                }
            }
        }))
        try {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            model.voiceSettings.phoneControl=false;model.voiceSettings.continuousConversation=true;model.voiceSettings.realtimeConversation=false
            model.endpointSettings.save(VoiceEndpoint(server.url("/").toString().replaceFirst("http://","ws://")),true)
            withContext(Dispatchers.Main.immediate){model.connect(startWhenReady=true)}
            val ws=withTimeout(15000){hello.await()}
            withContext(Dispatchers.Main.immediate){
                assertTrue(model.pendingStart.value);model.toggleListening();assertFalse(model.pendingStart.value)
                if(startAgain)model.toggleListening()
            }
            ws.send("""{"type":"hello","session_id":"cancelled-start"}""")
            withTimeout(15000){model.connected.first{it}}
            if(startAgain) {
                withTimeout(15000){started.await()}
                assertEquals(1,starts.get());assertEquals(ConversationState.LISTENING,model.state.value)
            } else {
                assertEquals(ConversationState.IDLE,model.state.value);assertFalse(model.audioRoute.value.active)
                assertFalse(model.backgroundConversation.value);assertEquals(0,starts.get())
                ws.close(1012,"test server restart")
                withTimeout(10000){model.connected.first{!it}}
                delay(3000)
                assertEquals(1,server.requestCount);assertFalse(model.pendingStart.value)
                assertEquals(0,starts.get());assertFalse(model.audioRoute.value.active)
            }
        } finally {
            withContext(Dispatchers.Main.immediate){model.disconnect()}
            if(hello.isCompleted && withTimeoutOrNull(5000){closed.await()}==null)hello.await().cancel()
            model.endpointSettings.save(endpoint,enabled)
            model.voiceSettings.phoneControl=phone;model.voiceSettings.continuousConversation=continuous;model.voiceSettings.realtimeConversation=realtime
            server.shutdown()
        }
    }
    @Test fun emptyAndRepeatedSttKeepTheCurrentReplyAndRelaxedPauseSurvivesReload() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val memory=ConversationMemory();memory.enabled(false)
        val model=withContext(Dispatchers.Main.immediate){OpenNomiCloudViewModel(context,memory)}
        val endpoint=model.endpointSettings.connection();val enabled=model.endpointSettings.enabled
        val phone=model.voiceSettings.phoneControl;val realtime=model.voiceSettings.realtimeConversation
        val prefs=context.getSharedPreferences("open_nomi_voice",0)
        val hadPause=prefs.contains("endpoint_millis");val oldPause=model.voiceSettings.endpointMillis
        val server=MockWebServer();server.start()
        val closed=CompletableDeferred<Unit>();val opened=CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
            override fun onOpen(ws:WebSocket,response:Response){opened.complete(ws)}
            override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
            override fun onClosed(ws:WebSocket,code:Int,reason:String){closed.complete(Unit)}
            override fun onMessage(ws:WebSocket,text:String){
                val event=JSONObject(text)
                if(event.optString("type")=="hello")ws.send("""{"type":"hello","session_id":"stt-test"}""")
            }
        }))
        try {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
            model.voiceSettings.phoneControl=false;model.voiceSettings.realtimeConversation=false
            model.voiceSettings.endpointMillis=1200L
            assertEquals(1200L,ai.opennomi.app.voice.VoiceSettings(context).endpointMillis)
            model.endpointSettings.save(VoiceEndpoint(server.url("/").toString().replaceFirst("http://","ws://")),true)
            withContext(Dispatchers.Main.immediate){model.connect()}
            withTimeout(15000){model.connected.first{it}}
            withContext(Dispatchers.Main.immediate){
                model.toggleListening()
                model.onStt("你好")
                model.onResponseText("你好，我在听。")
                model.onStt("  ")
                model.onStt(" 你好 ")
                assertEquals("你好",model.heard.value)
                assertEquals("你好，我在听。",model.response.value)
                assertEquals(ConversationState.THINKING,model.state.value)
                model.onTtsState("start")
                model.onStt("\t")
                assertEquals(ConversationState.SPEAKING,model.state.value)
                assertEquals("你好",model.heard.value)
            }
        } finally {
            withContext(Dispatchers.Main.immediate){model.disconnect()}
            if(opened.isCompleted && withTimeoutOrNull(5000){closed.await()}==null)opened.await().cancel()
            model.endpointSettings.save(endpoint,enabled)
            model.voiceSettings.phoneControl=phone;model.voiceSettings.realtimeConversation=realtime
            if(hadPause)model.voiceSettings.endpointMillis=oldPause else prefs.edit().remove("endpoint_millis").commit()
            server.shutdown()
        }
    }
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
