package ai.opennomi.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import ai.opennomi.app.voice.FishAudioActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FishScreenSmokeTest {
    @Test fun independentReaderAndReplyTabsRenderWithoutAccountOrCloudConnection() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        ActivityScenario.launch<FishAudioActivity>(Intent(context,FishAudioActivity::class.java)).use {
            assertTrue(device.wait(Until.hasObject(By.text("让文字有声音")),10000))
            assertNotNull(device.findObject(By.text("开始朗读")))
            val output=File(context.getExternalFilesDir(null),"fish-workstation.png")
            assertTrue(device.takeScreenshot(output))
            device.findObject(By.text("回复接入")).click()
            assertTrue(device.wait(Until.hasObject(By.text("同一个声音，陪你聊天")),5000))
            device.findObject(By.text("独立朗读")).click()
            assertTrue(device.wait(Until.hasObject(By.text("让文字有声音")),5000))
        }
    }
}
