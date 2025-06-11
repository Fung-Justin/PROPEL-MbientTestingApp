package com.example.mbienttestingapp


import android.bluetooth.BluetoothManager
import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import com.example.mbienttestingapp.ui.theme.MbientTestingAppTheme
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.android.BtleService.LocalBinder
import com.mbientlab.metawear.module.Accelerometer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


import java.io.FileOutputStream
import java.io.File
import java.io.IOException
import java.util.LinkedList

 var sessionFolder: File? = null

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
        fetchBoards()
    }

    override fun onServiceDisconnected(componentName: ComponentName) {}

    private fun fetchBoards(){
        logToFile("retrieveBoard: Retrieving....")
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        Log.i("Board", "fetching boards!")

        val boardQueue = LinkedList<Pair<MetaWearBoard, Int>>()

        macAddress.forEachIndexed { idx, address ->
            val remoteDevice = btManager.adapter.getRemoteDevice(address)

            Log.i("Board", "Getting board for $address, set remote device")

            serviceBinder?.getMetaWearBoard(remoteDevice)?.also { board ->
                logToFile("retrieveBoard: Retrieved board for $address")
                boardQueue.add(Pair(board, idx))
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            while (boardQueue.isNotEmpty()) {
                val (board, idx) = boardQueue.remove()
                logToFile("retrieveBoard: attempting to connect with Sensor ${idx + 1}")
                connect(board, idx)
            }
        }
    }
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














