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
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) ai.opennomi.app.voice.NomiVoiceService.toggle(this)
            }
            LaunchedEffect(Unit) { cloudViewModel.connect() }
            OpenNomiApp(cloudViewModel) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) ai.opennomi.app.voice.NomiVoiceService.toggle(this)
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
