package com.tassiolima.rawcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tassiolima.rawcam.ui.CameraScreen
import com.tassiolima.rawcam.ui.ReviewScreen

private val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var granted by remember { mutableStateOf(hasAllPermissions()) }

            val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                granted = result.values.all { it }
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    if (!granted) {
                        PermissionRequestScreen { permissionLauncher.launch(REQUIRED_PERMISSIONS) }
                    } else {
                        val review = viewModel.reviewMedia
                        if (review != null) {
                            ReviewScreen(
                                media = review,
                                onBack = { viewModel.closeReview() },
                                onDelete = { viewModel.deleteMedia(it) },
                            )
                        } else {
                            CameraScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeCameraIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        viewModel.pauseCamera()
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Button(onClick = onRequest, modifier = Modifier.padding(24.dp)) {
            Text("Conceder permissões de câmera e microfone")
        }
    }
}
