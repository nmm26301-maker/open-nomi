package ai.opennomi.app

import ai.opennomi.app.screen.VisionApi
import ai.opennomi.app.screen.VisionConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VisionApiRegressionTest {
    @Test fun acceptsBaseOrFullEndpointWithoutDuplicatingPath() {
        assertEquals("https://example.com/v1/chat/completions",VisionApi.endpoint("https://example.com").toString())
        assertEquals("https://example.com/v1/chat/completions",VisionApi.endpoint("https://example.com/v1/chat/completions/").toString())
        assertEquals("${VisionApi.FREE_BASE}/chat/completions",VisionApi.endpoint(VisionApi.FREE_BASE).toString())
    }
    @Test fun credentialsCannotHideInAddress() {
        listOf("https://user:password@example.com/v1", "https://example.com/v1?key=private", "http://example.com/v1", "http://192.168.example.com/v1", "http://192.168.1.1.example.com/v1", "http://10.evil.example/v1").forEach { assertTrue(runCatching {VisionApi.endpoint(it)}.isFailure) }
    }
    @Test fun glmDisablesThinkingAndUsesDocumentedBase64() {
        val cfg=VisionConnection(VisionApi.FREE_BASE,VisionApi.FREE_MODEL,"test-key")
        assertEquals("disabled",VisionApi.body(cfg,JSONArray()).getJSONObject("thinking").getString("type"))
        assertEquals("aW1hZ2U=",VisionApi.imageUrl(cfg,"aW1hZ2U="))
        assertEquals("data:image/jpeg;base64,aW1hZ2U=",VisionApi.imageUrl(cfg.copy(base="https://example.com/v1"),"aW1hZ2U="))
    }
    @Test fun realHttpRequestCarriesImageAndReadsSmallResponse() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"图片里是一只猫\"}}]}"))
            val cfg=VisionConnection(server.url("/v1").toString(),"vision-model","local-test-key")
            val messages=JSONArray().put(JSONObject().put("role","user").put("content",JSONArray().put(JSONObject().put("type","image_url").put("image_url",JSONObject().put("url",VisionApi.imageUrl(cfg,"aW1hZ2U="))))))
            assertEquals("图片里是一只猫",VisionApi().complete(cfg,messages))
            val request=server.takeRequest()
            assertEquals("/v1/chat/completions",request.path)
            assertEquals("Bearer local-test-key",request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("data:image/jpeg;base64,aW1hZ2U="))
        } finally {server.shutdown()}
    }
    @Test fun errorDoesNotEchoProviderBodyOrSecret() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("private-key-from-server"))
            val error=runCatching {VisionApi().complete(VisionConnection(server.url("/v1").toString(),"vision","test"),JSONArray())}.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("密钥无效"))
            assertFalse(error?.message.orEmpty().contains("private-key"))
        } finally {server.shutdown()}
    }
    @Test fun parsesContentPartsAndRejectsEmptyReasoningOnlyReply() {
        assertEquals("识别完成",VisionApi.content(JSONObject("{\"choices\":[{\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"识别完成\"}]}}]}")))
        assertTrue(runCatching{VisionApi.content(JSONObject("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null,\"reasoning_content\":\"thinking\"}}]}"))}.isFailure)
    }
}
