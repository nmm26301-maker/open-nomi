package ai.opennomi.app

import ai.opennomi.app.voice.FishAudioApi
import ai.opennomi.app.voice.FishAudioConfig
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class FishAudioRegressionTest {
    @Test fun sendsOfficialJsonContractAndReturnsBinaryUnchanged() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val audio = byteArrayOf(73, 68, 51, 0, 127, -1, -128, 10)
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(Buffer().write(audio)))
            val cfg = FishAudioConfig("test-key", "voice-id", "s2.1-pro", server.url("/").toString(), 1.2)
            assertArrayEquals(audio, FishAudioApi().synthesize(cfg, "你好，打开相机。"))
            val request = server.takeRequest()
            assertEquals("/v1/tts", request.path)
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            assertEquals("s2.1-pro", request.getHeader("model"))
            assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("你好，打开相机。", body.getString("text"))
            assertEquals("mp3", body.getString("format"))
            assertEquals("voice-id", body.getString("reference_id"))
            assertEquals(1.2, body.getJSONObject("prosody").getDouble("speed"), 0.001)
            assertFalse(body.has("model"))
        } finally { server.shutdown() }
    }
    @Test fun blankVoiceUsesProviderDefaultAndV1BaseIsAccepted() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(73, 68, 51))))
            FishAudioApi().synthesize(FishAudioConfig("test", base = server.url("/v1/").toString()), "你好")
            val request = server.takeRequest()
            assertEquals("/v1/tts", request.path)
            assertFalse(JSONObject(request.body.readUtf8()).has("reference_id"))
        } finally { server.shutdown() }
    }
    @Test fun rejectsServiceErrorsWithoutLeakingBodyOrRetrying() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (code in listOf(401, 402, 403, 404, 429, 503)) {
                server.enqueue(MockResponse().setResponseCode(code).setBody("test-key-secret-provider-body"))
                val error = runCatching { FishAudioApi().synthesize(FishAudioConfig("test-key", base = server.url("/").toString()), "你好") }.exceptionOrNull()
                assertNotNull(error)
                assertTrue(error!!.message.orEmpty().contains("FishAudio"))
                assertFalse(error.message.orEmpty().contains("test-key"))
                assertFalse(error.message.orEmpty().contains("provider-body"))
            }
            assertEquals(6, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun doesNotForwardSecretOnRedirect() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
            val result = runCatching { FishAudioApi().synthesize(FishAudioConfig("test", base = server.url("/").toString()), "你好") }
            assertTrue(result.isFailure)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun rejectsEmptyJsonAndOversizedAudio() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(""))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"message\":\"secret\"}"))
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", FishAudioApi.MAX_AUDIO_BYTES + 1))
            repeat(3) {
                val error = runCatching { FishAudioApi().synthesize(FishAudioConfig("test", base = server.url("/").toString()), "你好") }.exceptionOrNull()
                assertNotNull(error)
                assertFalse(error!!.message.orEmpty().contains("secret"))
            }
        } finally { server.shutdown() }
    }
    @Test fun cancellingReplyCancelsPendingHttp() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.Default) { FishAudioApi().synthesize(FishAudioConfig("test", base = server.url("/").toString()), "你好") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun preservesLongChineseAndEmojiRepliesWhenSplitting() {
        val text = "中".repeat(499) + "😀" + "文。".repeat(1000)
        val parts = FishAudioApi.speechParts(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 500 })
        assertTrue(parts.none { Character.isLowSurrogate(it.first()) || Character.isHighSurrogate(it.last()) })
        assertTrue(runCatching { FishAudioApi.speechParts("中".repeat(12001)) }.isFailure)
    }
    @Test fun credentialsAndInsecureAddressesFailBeforeSending() {
        for (base in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?key=secret", "https://example.com#secret")) {
            assertTrue(runCatching { FishAudioConfig("test", base = base).validate() }.isFailure)
        }
        assertTrue(runCatching { FishAudioConfig("").validate() }.isFailure)
        assertTrue(runCatching { FishAudioConfig("test", model = "bad\nheader").validate() }.isFailure)
        assertTrue(runCatching { FishAudioConfig("test", speed = 3.0).validate() }.isFailure)
    }
}
