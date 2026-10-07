package ai.opennomi.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversationModeSmokeTest {
    @Test fun homeCanLeaveNativeControlAndSelectConversationWithoutHiddenSettings() {
        val context=ApplicationProvider.getApplicationContext<NomiApplication>()
        context.cloudModel.voiceSettings.phoneControl=true
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("聊天")),10000))
            assertNotNull(device.findObject(By.text("手机控制")))
            scenario.onActivity{context.cloudModel.beginNativeControl()}
            device.findObject(By.text("聊天")).click();device.waitForIdle()
            scenario.onActivity{
                assertFalse(context.cloudModel.nativePhoneControl)
                assertFalse(context.cloudModel.voiceSettings.phoneControl)
                assertFalse(context.cloudModel.backgroundConversation.value)
            }
            val output=File(context.getExternalFilesDir(null),"conversation-home.png")
            assertTrue(device.takeScreenshot(output))
            device.executeShellCommand("cp ${output.absolutePath} /data/local/tmp/conversation-home.png")
            scenario.onActivity{context.cloudModel.disconnect()}
        }
    }
}
