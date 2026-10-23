package ai.opennomi.app

import android.content.Intent
import android.net.ConnectivityManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import ai.opennomi.app.screen.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranslationSmokeTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun realPhoneModelDownloadsTranslatesAndReloadsWithoutNetwork() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val engine=PhoneTranslator();val reloaded=PhoneTranslator()
        val oldWifi=device.executeShellCommand("settings get global wifi_on").trim()
        val oldData=device.executeShellCommand("settings get global mobile_data").trim()
        try {
            withTimeout(240000){engine.prepare("en",true)}
            val first=engine.translate("Hello world.","auto")
            assertTrue(first.any{it in '\u4e00'..'\u9fff'});assertNotEquals("Hello world.",first)
            assertEquals("A repeated exact phrase must retain its actual Chinese translation",first,engine.translate("Hello world.","en"))
            val cache=PhoneTranslator::class.java.getDeclaredField("cache").apply{isAccessible=true}.get(engine) as PhoneTranslationCache
            assertEquals("Successful model output must enter the exact phrase cache",first,cache.cached("en","Hello world."))
            engine.close()
            assertNull("Closing must clear cached source and translation text",cache.cached("en","Hello world."))
            device.executeShellCommand("svc wifi disable");device.executeShellCommand("svc data disable")
            val network=context.getSystemService(ConnectivityManager::class.java)
            withTimeout(15000){while(network.activeNetwork!=null)delay(100)}
            assertNull("The offline translation check must have no active network",network.activeNetwork)
            val offline=withTimeout(45000){reloaded.translate("Good morning.","en")}
            assertTrue(offline.any{it in '\u4e00'..'\u9fff'});assertNotEquals("Good morning.",offline)
        } finally {
            device.executeShellCommand("svc wifi ${if(oldWifi=="0")"disable" else "enable"}")
            device.executeShellCommand("svc data ${if(oldData=="0")"disable" else "enable"}")
            engine.close();reloaded.close()
        }
    }
    @Test fun translationPageWorksWithoutVisionKeysAndShowsActualChineseResult() = runBlocking<Unit> {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        val cfg=ScreenAssistant.settings();val phone=cfg.phoneTranslation;val hy=cfg.hyMtTranslation;val source=cfg.translationSource;val cellular=cfg.translationCellular
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            cfg.phoneTranslation=true;cfg.hyMtTranslation=false;cfg.translationSource="en";cfg.translationCellular=true
            assertFalse(cfg.modelReady());assertTrue(cfg.apiKey.isBlank())
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("en",true)}
            ActivityScenario.launch<WorkspaceActivity>(Intent(context,WorkspaceActivity::class.java).putExtra("tab","translation")).use { scenario ->
                compose.onNodeWithText("文字翻译").performClick()
                compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Hello world.")
                compose.onNodeWithText("翻译为中文").performScrollTo().performClick()
                compose.waitUntil(30000){compose.onAllNodesWithText("世界",substring=true).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("世界",substring=true).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("由 Google 翻译").assertIsDisplayed()
                val output=File(context.getExternalFilesDir(null),"translation-workspace.png")
                // Render our own synthetic test page; retain production FLAG_SECURE.
                // UiDevice screenshots can return null for protected windows.
                scenario.onActivity { activity ->
                    val view=activity.window.decorView
                    val capture=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(capture))
                        FileOutputStream(output).use { assertTrue(capture.compress(Bitmap.CompressFormat.PNG,100,it)) }
                    } finally {capture.recycle()}
                }
                assertTrue(output.length()>0)
                device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/translation-workspace.png")
            }
        } finally {cfg.phoneTranslation=phone;cfg.hyMtTranslation=hy;cfg.translationSource=source;cfg.translationCellular=cellular}
    }
    @Test fun configuredLibreServiceReceivesARealTranslateRequestAndReturnsChinese() = runBlocking<Unit> {
        val cfg=ScreenAssistant.settings();val phone=cfg.phoneTranslation;val libre=cfg.libre;val key=cfg.libreKey
        val server=MockWebServer();server.start()
        try {
            cfg.phoneTranslation=false;cfg.libre=server.url("/").toString();cfg.libreKey="test-key"
            server.enqueue(MockResponse().setBody("""{"translatedText":"你好，世界。"}"""))
            assertEquals("你好，世界。",ScreenAssistant.translate("Hello world."))
            val request=server.takeRequest(5,TimeUnit.SECONDS) ?: error("Translation request missing")
            assertEquals("/translate",request.path)
            val body=JSONObject(request.body.readUtf8());assertEquals("Hello world.",body.getString("q"))
            assertEquals("zh",body.getString("target"));assertEquals("test-key",body.getString("api_key"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("provider-secret"))
            val failure=runCatching{ScreenAssistant.translate("Try again.")}.exceptionOrNull()
            assertNotNull(failure);assertFalse(failure!!.message.orEmpty().contains("provider-secret"))
        } finally {cfg.phoneTranslation=phone;cfg.libre=libre;cfg.libreKey=key;server.shutdown()}
    }
}

