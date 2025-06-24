package com.example.mbienttestingapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

        // Set the ViewModel instance for global access
        SensorViewModel.setInstance(sensorViewModel)

        // Set fallback log file
        logFile = sensorViewModel.logFile

        enableEdgeToEdge()
        setContent {
            MbientTestingAppTheme {
                MainView(sensorViewModel)
            }
        }
    }
}