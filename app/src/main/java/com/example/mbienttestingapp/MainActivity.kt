package com.example.mbienttestingapp


import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mbienttestingapp.ui.theme.MbientTestingAppTheme
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.android.BtleService.LocalBinder

import java.io.FileOutputStream
import java.io.File
import java.io.IOException

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

        fetchBoards()
//        fun retrieveBoard(sensor: Sensor): MetaWearBoard? {
//            logToFile("retrieveBoard: Retrieving....")
//            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//            val remoteDevice = btManager.adapter.getRemoteDevice(sensor.macAddress)
//            return serviceBinder?.getMetaWearBoard(remoteDevice)?.also { board ->
//
//                sensor.imu = board
//                logToFile(
//                    "retrieveBoard: Retrieved board for $sensor.macAddress"
//                )
//            }
//        }


        enableEdgeToEdge()
        setContent {
            MbientTestingAppTheme {
               MainView()
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

    private fun fetchBoards(){
        logToFile("retrieveBoard: Retrieving....")
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        macAddress.forEachIndexed { idx, address ->
            val remoteDevice = btManager.adapter.getRemoteDevice(address)

            serviceBinder?.getMetaWearBoard(remoteDevice)?.also { board ->

                val sensor = Sensor(
                    address,
                    "Sensor${idx+1}",
                    board
                )

                addSensor(sensor)

                logToFile(
                    "retrieveBoard: Retrieved board for $address"
                )
            }

        }
    }
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














