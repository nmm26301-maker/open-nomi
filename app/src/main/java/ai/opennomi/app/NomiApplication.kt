package ai.opennomi.app
import android.app.Application
import java.io.File
import kotlin.concurrent.thread
class NomiApplication : Application() {
    val conversationMemory by lazy { ai.opennomi.app.voice.LocalConversationMemory(this).conversation }
    val audioRoutes by lazy { ai.opennomi.app.audio.ConversationAudioRoutes(this) }
    val cloudModel by lazy { OpenNomiCloudViewModel(this) }
    override fun onCreate() {
        super.onCreate()
        ai.opennomi.app.screen.ScreenAssistant.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { ai.opennomi.app.screen.ScreenState.ownForeground = true }
            override fun onActivityPaused(activity: android.app.Activity) { ai.opennomi.app.screen.ScreenState.ownForeground = false }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // Retire only app-private offline models; preserve account and device identity.
        thread(name = "retire-unused-models", isDaemon = true) {
            runCatching { File(filesDir, "offline-models").deleteRecursively() }
        }
    }
}
