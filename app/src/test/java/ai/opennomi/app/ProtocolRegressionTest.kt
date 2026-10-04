package ai.opennomi.app

import ai.opennomi.app.network.XiaozhiProtocolClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProtocolRegressionTest {
    @Test fun registeredVersionControlsHandshakeAndBothDirectionsOfAudio() {
        for(version in 1..3) {
            val server=MockWebServer();val listener=Listener()
            val messages=LinkedBlockingQueue<String>();val uploads=LinkedBlockingQueue<ByteArray>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onMessage(ws:WebSocket,text:String) {
                    messages.add(text)
                    if(JSONObject(text).optString("type")=="hello") {
                        ws.send("""{"type":"hello","transport":"websocket","session_id":"wire","audio_params":{"sample_rate":24000}}""")
                        ws.send(ByteString.of(*ai.opennomi.app.network.XiaozhiAudioWire.encode(byteArrayOf(21,22),version)))
                    }
                }
                override fun onMessage(ws:WebSocket,bytes:ByteString){uploads.add(bytes.toByteArray())}
                override fun onClosing(ws:WebSocket,code:Int,reason:String){ws.close(code,reason)}
            }))
            server.start()
            val client=XiaozhiProtocolClient(server.url("/").toString().replace("http","ws"),"","device","client",listener,false,version)
            try {
                client.connect();assertEquals("rate:24000",listener.events.poll(5,TimeUnit.SECONDS));assertEquals("open",listener.events.poll(5,TimeUnit.SECONDS))
                assertEquals(version.toString(),server.takeRequest(5,TimeUnit.SECONDS)!!.getHeader("Protocol-Version"))
                assertEquals(version,JSONObject(messages.poll(5,TimeUnit.SECONDS)!!).getInt("version"))
                assertArrayEquals(byteArrayOf(21,22),listener.audio.poll(5,TimeUnit.SECONDS))
                client.sendAudio(byteArrayOf(31,32));assertArrayEquals(ai.opennomi.app.network.XiaozhiAudioWire.encode(byteArrayOf(31,32),version),uploads.poll(5,TimeUnit.SECONDS))
                client.sendText("用小智原声回答");val request=JSONObject(messages.poll(5,TimeUnit.SECONDS)!!)
                assertEquals("text",request.getString("source"));assertEquals("manual",request.getString("mode"));assertEquals("detect",request.getString("state"))
            } finally {client.disconnect();server.shutdown()}
        }
    }
    private class Listener : XiaozhiProtocolClient.Listener {
        val events = LinkedBlockingQueue<String>()
        val audio = LinkedBlockingQueue<ByteArray>()
        val playbackOrder = LinkedBlockingQueue<String>()
        val text = LinkedBlockingQueue<String>()
        override fun onOpen() { events.add("open") }
        override fun onClosed(error: Throwable?) { events.add("closed") }
        override fun onStt(text: String) {}
        override fun onResponseText(value: String) { text.add(value) }
        override fun onTtsState(state: String) { playbackOrder.add("tts:$state") }
        override fun onEmotion(emotion: String) {}
        override fun onAudio(opus: ByteArray) { audio.add(opus); playbackOrder.add("audio:${opus.first()}") }
        override fun onAudioFormat(sampleRate: Int) { events.add("rate:$sampleRate") }
    }
    @Test fun waitsForHelloNegotiates24kAndFiltersWireEvents() {
        val server = MockWebServer(); val listener = Listener()
        val socket = AtomicReference<WebSocket>(); val hello = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { socket.set(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { hello.add(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = XiaozhiProtocolClient(server.url("/").toString().replace("http", "ws"), "", "device", "client", listener)
        try {
            client.connect()
            val first = JSONObject(hello.poll(5,TimeUnit.SECONDS) ?: error("hello not sent"))
            assertFalse(first.getJSONObject("features").getBoolean("aec"))
            assertEquals(16000, first.getJSONObject("audio_params").getInt("sample_rate"))
            assertNull(listener.events.poll(150,TimeUnit.MILLISECONDS))
            socket.get().send("""{"type":"hello","session_id":"test","audio_params":{"sample_rate":24000,"channels":1,"format":"opus"}}""")
            assertEquals("rate:24000",listener.events.poll(5,TimeUnit.SECONDS))
            assertEquals("open",listener.events.poll(5,TimeUnit.SECONDS))
            socket.get().send(ByteString.of(*byteArrayOf(0,0,0,3,11,12)))
            socket.get().send(ByteString.of(*byteArrayOf(0,0,0,2,11,12)))
            assertArrayEquals(byteArrayOf(11,12),listener.audio.poll(5,TimeUnit.SECONDS))
            assertNull(listener.audio.poll(100,TimeUnit.MILLISECONDS))
            socket.get().send("""{"type":"tts","state":"sentence_start","text":"你好"}""")
            socket.get().send("""{"type":"tts","state":"sentence_end","text":"你好"}""")
            assertEquals("你好",listener.text.poll(5,TimeUnit.SECONDS))
            assertNull(listener.text.poll(100,TimeUnit.MILLISECONDS))
        } finally { client.disconnect(); server.shutdown() }
    }
    @Test fun incompatibleAudioNeverReportsConnected() {
        val server = MockWebServer(); val listener = Listener()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"hello","session_id":"test","audio_params":{"sample_rate":44100,"channels":1,"format":"opus"}}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = XiaozhiProtocolClient(server.url("/").toString().replace("http", "ws"), "", "device", "client", listener)
        try { client.connect(); assertEquals("closed",listener.events.poll(5,TimeUnit.SECONDS)); assertNull(listener.events.poll(100,TimeUnit.MILLISECONDS)) }
        finally { client.disconnect(); server.shutdown() }
    }

    @Test fun receivesQueuedAudioBeforeThePlaybackDrainSignal() {
        val server = MockWebServer(); val listener = Listener()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).optString("type") != "hello") return
                webSocket.send("""{"type":"hello","session_id":"ordered","audio_params":{"sample_rate":24000}}""")
                webSocket.send("""{"type":"tts","state":"start"}""")
                webSocket.send(ByteString.of(*byteArrayOf(0,0,0,2,11,12)))
                webSocket.send(ByteString.of(*byteArrayOf(0,0,0,2,21,22)))
                webSocket.send("""{"type":"tts","state":"stop"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = XiaozhiProtocolClient(server.url("/").toString().replace("http", "ws"), "", "device", "client", listener)
        try {
            client.connect()
            assertEquals("tts:start", listener.playbackOrder.poll(5, TimeUnit.SECONDS))
            assertEquals("audio:11", listener.playbackOrder.poll(5, TimeUnit.SECONDS))
            assertEquals("audio:21", listener.playbackOrder.poll(5, TimeUnit.SECONDS))
            assertEquals("tts:stop", listener.playbackOrder.poll(5, TimeUnit.SECONDS))
        } finally { client.disconnect(); server.shutdown() }
    }
    @Test fun closingBeforeHelloAckNeverReopensTheCancelledConnection() {
        val server = MockWebServer(); val listener = Listener()
        val firstHello = LinkedBlockingQueue<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { firstHello.add(webSocket) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        }))
        server.start()
        val client = XiaozhiProtocolClient(server.url("/").toString().replace("http", "ws"), "", "device", "client", listener)
        try {
            client.connect()
            val socket = firstHello.poll(5, TimeUnit.SECONDS) ?: error("hello not received")
            client.disconnect()
            socket.send("""{"type":"hello","session_id":"late"}""")
            assertNull(listener.events.poll(500, TimeUnit.MILLISECONDS))
            client.sendListen(); client.sendAudio(byteArrayOf(1,2))
            assertNull(firstHello.poll(100, TimeUnit.MILLISECONDS))
        } finally { client.disconnect(); server.shutdown() }
    }
    @Test fun textOnlyModelReplyReachesSpeechFallbackWithoutAnAudioPacket() {
        val server=MockWebServer();val listener=Listener()
        server.enqueue(MockResponse().withWebSocketUpgrade(object:WebSocketListener() {
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(JSONObject(text).optString("type")!="hello")return
                webSocket.send("""{"type":"hello","session_id":"text-only"}""")
                webSocket.send("""{"type":"llm","text":"这是屏幕中的回答"}""")
                webSocket.send("""{"type":"tts","state":"stop"}""")
            }
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){webSocket.close(code,reason)}
        }))
        server.start()
        val client=XiaozhiProtocolClient(server.url("/").toString().replace("http","ws"),"","device","client",listener)
        try {
            client.connect()
            assertEquals("这是屏幕中的回答",listener.text.poll(5,TimeUnit.SECONDS))
            assertEquals("tts:stop",listener.playbackOrder.poll(5,TimeUnit.SECONDS))
            assertNull(listener.audio.poll(100,TimeUnit.MILLISECONDS))
        } finally { client.disconnect();server.shutdown() }
    }
}
