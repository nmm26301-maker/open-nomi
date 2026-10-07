package ai.opennomi.app

import ai.opennomi.app.voice.*
import ai.opennomi.app.network.VoiceEndpoint
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

class StreamingVoiceRegressionTest {
    @Test fun startsTheFirstSentenceBeforeTheReplyEnds() {
        val chunks=SpeechChunks()
        assertEquals(listOf("你好。"),chunks.take("你好。后面的回答还在生成"))
        assertEquals(listOf("后面的回答还在生成！"),chunks.take("你好。后面的回答还在生成！"))
    }
    @Test fun cumulativeAndSentenceEventsAreReadOnlyOnce() {
        val text=ResponseAccumulator();val chunks=SpeechChunks();var reply=""
        val spoken=mutableListOf<String>()
        for(event in listOf("你好。","你好。","你好。今天天气晴。","今天天气晴。")) {
            reply+=text.accept(event);spoken+=chunks.take(reply)
        }
        assertEquals(listOf("你好。","今天天气晴。"),spoken)
        assertTrue(chunks.take(reply,true).isEmpty())
    }
    @Test fun unfinishedTextWaitsUntilAnExplicitFlush() {
        val chunks=SpeechChunks()
        assertTrue(chunks.take("我还没有说完").isEmpty())
        assertEquals(listOf("我还没有说完"),chunks.take("我还没有说完",true))
        assertTrue(chunks.take("我还没有说完",true).isEmpty())
    }
    @Test fun preservesEmojiAndBoundsLongUnpunctuatedSentences() {
        val text="中".repeat(149)+"😀"+"文".repeat(300)
        val pieces=SpeechChunks().take(text,true)
        assertEquals(text,pieces.joinToString(""))
        assertTrue(pieces.all{it.length<=150})
        assertTrue(pieces.none{Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first())})
    }
    @Test fun aDecimalPointSplitBetweenTokensIsNotSpokenAsASentenceEnd() {
        val chunks=SpeechChunks()
        assertTrue(chunks.take("数值是3.").isEmpty())
        assertEquals(listOf("数值是3.14。"),chunks.take("数值是3.14。"))
    }
    @Test fun excessiveReplyTextIsRejectedBeforeQueueing() {
        assertTrue(runCatching{SpeechChunks().take("中".repeat(12001),true)}.isFailure)
    }
    @Test fun pcmSamplesSurviveOddHttpChunks() {
        val framer=Pcm16Framer();val result=ByteArrayOutputStream()
        for(bytes in listOf(byteArrayOf(1),byteArrayOf(2,3),byteArrayOf(4,5,6,7),byteArrayOf(8)))result.write(framer.accept(bytes))
        framer.finish()
        assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),result.toByteArray())
    }
    @Test fun compressedOrContainerAudioIsRejectedEvenIfTheSignatureIsSplit() {
        for(signature in listOf("RIFF","OggS","ID3x","fLaC")) {
            val framer=Pcm16Framer();framer.accept(signature.take(2).toByteArray())
            assertTrue(runCatching{framer.accept(signature.drop(2).toByteArray())}.isFailure)
        }
    }
    @Test fun truncatedOrEmptyPcmFailsAtEof() {
        for(bytes in listOf(ByteArray(0),byteArrayOf(0,1),byteArrayOf(0,1,2,3,4))) {
            val framer=Pcm16Framer();framer.accept(bytes)
            assertTrue(runCatching{framer.finish()}.isFailure)
        }
    }
    @Test fun pcmIsDeliveredBeforeHttpEofAndUsesTheDocumentedContract() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val bytes=ByteArray(48000){(it%100).toByte()}
            server.enqueue(MockResponse().setHeader("Content-Type","application/octet-stream")
                .setBody(Buffer().write(bytes)).throttleBody(2400,80,TimeUnit.MILLISECONDS))
            val first=CompletableDeferred<Unit>();val result=ByteArrayOutputStream()
            val job=launch(Dispatchers.Default){FishAudioApi().streamPcm(FishAudioConfig("test", "voice",base=server.url("/").toString()),"你好"){chunk->result.write(chunk);first.complete(Unit)}}
            withTimeout(3000){first.await()};assertTrue(job.isActive)
            withTimeout(10000){job.join()}
            assertArrayEquals(bytes,result.toByteArray())
            val request=server.takeRequest();val body=JSONObject(request.body.readUtf8())
            assertEquals("pcm",body.getString("format"));assertEquals(24000,body.getInt("sample_rate"))
            assertEquals(100,body.getInt("chunk_length"));assertEquals("balanced",body.getString("latency"))
            assertEquals("voice",body.getString("reference_id"));assertEquals("Bearer test",request.getHeader("Authorization"))
        } finally {server.shutdown()}
    }
    @Test fun cancellationStopsAnInFlightPcmDownload() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(48000))).throttleBody(2400,100,TimeUnit.MILLISECONDS))
            val first=CompletableDeferred<Unit>()
            val job=launch(Dispatchers.Default){FishAudioApi().streamPcm(FishAudioConfig("test",base=server.url("/").toString()),"你好"){first.complete(Unit)}}
            withTimeout(3000){first.await()};withTimeout(2000){job.cancelAndJoin()}
            assertTrue(job.isCancelled);assertEquals(1,server.requestCount)
        } finally {server.shutdown()}
    }
    @Test fun pcmServiceErrorsAndWrongContentTypesNeverReachTheSpeaker() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            for(type in listOf("application/json","audio/mpeg","audio/wav","text/plain")) {
                server.enqueue(MockResponse().setHeader("Content-Type",type).setBody("provider-secret"))
                val error=runCatching{FishAudioApi().streamPcm(FishAudioConfig("test",base=server.url("/").toString()),"你好"){fail("Must not play a non-PCM body")}}.exceptionOrNull()
                assertNotNull(error);assertFalse(error!!.message.orEmpty().contains("provider-secret"))
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("provider-secret"))
            assertTrue(runCatching{FishAudioApi().streamPcm(FishAudioConfig("test",base=server.url("/").toString()),"你好"){fail()}}.isFailure)
        } finally {server.shutdown()}
    }
    @Test fun alternateEndpointAcceptsSecureRemoteAndPrivateLanAddresses() {
        listOf("wss://voice.example/xiaozhi/v1/","ws://127.0.0.1:8000/xiaozhi/v1/","ws://192.168.1.2:8000/","ws://172.16.1.2/","ws://10.0.0.1/").forEach{VoiceEndpoint(it).validate()}
    }
    @Test fun alternateEndpointRejectsUnsupportedProtocolAndCredentialUrls() {
        listOf("https://voice.example/","ws://voice.example/","wss://user:secret@voice.example/","wss://voice.example/?key=secret","wss://voice.example/#secret").forEach{assertTrue(runCatching{VoiceEndpoint(it).validate()}.isFailure)}
        assertTrue(runCatching{VoiceEndpoint("wss://voice.example/",version=4).validate()}.isFailure)
        assertTrue(runCatching{VoiceEndpoint("wss://voice.example/","bad\nheader").validate()}.isFailure)
    }
}
