package com.example.mbienttestingapp


import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.mbienttestingapp.ui.theme.MbientTestingAppTheme
import com.mbientlab.metawear.MetaWearBoard

import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.android.BtleService.LocalBinder

import com.mbientlab.metawear.data.Acceleration
import java.io.FileOutputStream
import com.mbientlab.metawear.module.Accelerometer
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException


import java.text.DateFormat.getDateTimeInstance
import java.time.Instant


private var sessionFolder: File? = null

private var logFile: File? = null
class MainActivity : ComponentActivity(), ServiceConnection {
    private var serviceBinder: LocalBinder? = null




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind to the BtleService
        applicationContext.bindService(
            Intent(this, BtleService::class.java),
            this, Context.BIND_AUTO_CREATE
        )

        val sessionTime =   System.currentTimeMillis()
        sessionFolder = File(applicationContext.filesDir, "${sessionTime}_session")
        sessionFolder?.mkdirs()

        logFile = startNewLogFile("Logs", sessionTime.toString(), applicationContext)

        fun retrieveBoard(sensor: Sensor): MetaWearBoard? {
            logToFile("retrieveBoard: Retrieving....")
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val remoteDevice = btManager.adapter.getRemoteDevice(sensor.macAddress)
            return serviceBinder?.getMetaWearBoard(remoteDevice)?.also { board ->

                sensor.imu = board
                logToFile(
                    "retrieveBoard: Retrieved board for $sensor.macAddress"
                )
            }
        }


        enableEdgeToEdge()
        setContent {
            MbientTestingAppTheme {
                Column {
                    UI().Topbar(title = "Mbient Testing App")
                    LazyColumn {
                        items(items = sensorMap.values.toList(), key = { it.macAddress }) { sensor ->
                            UI().sensorCard(sensor = sensor, onConnectToggle = { toggleConnect(sensor) }, modifier = Modifier)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind the service when the activity is destroyed
        applicationContext.unbindService(this)
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        // Typecast the binder to the service's LocalBinder class
        serviceBinder = service as BtleService.LocalBinder
    }

    override fun onServiceDisconnected(componentName: ComponentName) {}
}


fun startNewCsvFile(baseFileName: String = "sensor_data",time: String, context: Context): File {
    val headers = listOf("ax", "ay", "az", "Time")

    val filename = "${baseFileName}_${time}.csv"

    val directory = sessionFolder ?: context.filesDir // Use sessionFolder if available, otherwise default

    var newCSV = File(directory, filename)
    var fileOutputStream = FileOutputStream(newCSV)
    logToFile(
        "CsvDataWriter: Successfully opened CSV file"
    )
    try {
        val headerRow = headers.joinToString(",") + "\n"
        fileOutputStream.write(headerRow.toByteArray())
    } catch (e: IOException) {
        logToFile(
            "CsvDataWriter: ERROR - Error writing CSV header "
        )
    }
    return newCSV
}

fun startNewLogFile(baseFileName: String = "Logs", time: String, context: Context): File {

    val filename = "${baseFileName}_${time}.txt"

    val directory = sessionFolder ?: context.filesDir // Use sessionFolder if available, otherwise default
    val newLogFile = File(directory, filename)
    Log.i("LogFileCreator", "Log file created at: ") // Keep one Log.i for initial creation
    return newLogFile
}

fun logToFile(message: String) {
    val time = System.currentTimeMillis()
    logFile?.appendText("$time - $message\n")
}














