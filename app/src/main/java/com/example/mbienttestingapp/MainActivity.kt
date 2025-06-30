package com.example.mbienttestingapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import com.example.mbienttestingapp.ui.theme.MbientTestingAppTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val sensorViewModel: SensorViewModel by viewModels()

    companion object {
        // Keep these for early logging before ViewModel is ready
        internal var logFile: File? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        SensorViewModel.setInstance(sensorViewModel)

        logFile = sensorViewModel.logFile

        enableEdgeToEdge()
        setContent {
            MbientTestingAppTheme {
                var permissionsGranted by remember { mutableStateOf(false) }

                if (!permissionsGranted) {
                    PermissionScreen(
                        onPermissionsGranted = {
                            permissionsGranted = true
                        }
                    )
                } else {
                    MainView(sensorViewModel)
                }
            }
        }
    }
}