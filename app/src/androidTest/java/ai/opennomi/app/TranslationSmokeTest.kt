package ai.opennomi.app

import android.content.Intent
import android.net.ConnectivityManager
import android.view.WindowManager
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
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TranslationSmokeTest {
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
            engine.close()
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
        val cfg=ScreenAssistant.settings();val phone=cfg.phoneTranslation;val source=cfg.translationSource;val cellular=cfg.translationCellular
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            cfg.phoneTranslation=true;cfg.translationSource="en";cfg.translationCellular=true
            withTimeout(240000){ScreenAssistant.phoneTranslator.prepare("en",true)}
            ActivityScenario.launch<WorkspaceActivity>(Intent(context,WorkspaceActivity::class.java).putExtra("tab","translation")).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("翻译为中文")),10000))
                val field=device.findObject(By.clazz("android.widget.EditText"));assertNotNull(field)
                field.text="Hello world."
                device.findObject(By.text("翻译为中文")).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("世界")),30000))
                assertNotNull(device.findObject(By.text("由 Google 翻译")))
                scenario.onActivity{it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
                val output=File(context.getExternalFilesDir(null),"translation-workspace.png")
                assertTrue(device.takeScreenshot(output));device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/translation-workspace.png")
            }
        } finally {cfg.phoneTranslation=phone;cfg.translationSource=source;cfg.translationCellular=cellular}
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
