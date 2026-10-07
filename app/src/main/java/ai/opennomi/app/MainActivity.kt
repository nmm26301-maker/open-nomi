package ai.opennomi.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import ai.opennomi.app.ui.OpenNomiApp

class MainActivity : ComponentActivity() {
    private val cloudViewModel: OpenNomiCloudViewModel get() = (application as NomiApplication).cloudModel

    override fun onPause() { if (!cloudViewModel.backgroundConversation.value) cloudViewModel.pauseConversation(); super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                if (it[Manifest.permission.RECORD_AUDIO] == true || androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) ai.opennomi.app.voice.NomiVoiceService.toggle(this)
            }
            LaunchedEffect(Unit) { if(!cloudViewModel.voiceSettings.phoneControl)cloudViewModel.connect() }
            OpenNomiApp(cloudViewModel) {
                if (cloudViewModel.backgroundConversation.value) ai.opennomi.app.voice.NomiVoiceService.stop(this)
                else {
                    val requested=listOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA) +
                        (if(android.os.Build.VERSION.SDK_INT >= 31)listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()) +
                        if(android.os.Build.VERSION.SDK_INT >= 33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
                    val missing=requested.filter { androidx.core.content.ContextCompat.checkSelfPermission(this,it)!=android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if(missing.isEmpty())ai.opennomi.app.voice.NomiVoiceService.start(this,ai.opennomi.app.screen.ScreenState.state.value.active)
                    else permission.launch(missing.toTypedArray())
                }
            }
        }
    }
}
