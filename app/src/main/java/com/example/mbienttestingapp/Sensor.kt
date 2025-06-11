package com.example.mbienttestingapp

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import bolts.Task
import com.mbientlab.metawear.Data
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.data.Acceleration
import com.mbientlab.metawear.data.AngularVelocity
import com.mbientlab.metawear.module.Accelerometer
import com.mbientlab.metawear.module.Gyro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

//dictionary of sensors
var macAddress: List<String> = listOf(
    "E9:9B:AA:92:6A:F5", //Sensor 1
    "E2:81:A5:DD:A7:DB"
)

private val _sensorList = MutableStateFlow<Map<String, Sensor>>(emptyMap())
val sensorList: StateFlow<Map<String, Sensor>> = _sensorList

fun addSensor(sensor: Sensor) {
    _sensorList.update { currentMap ->
        currentMap + (sensor.macAddress to sensor)
    }
}

class Sensor(val macAddress: String, val sensorName: String = "", val imu: MetaWearBoard) {

    var isConnected by mutableStateOf(false)
    var isStreaming by mutableStateOf(false)
    var batteryLevel by mutableStateOf("-")
    var rssi by mutableStateOf("-")
    val data: List<ArrayList<Data>> = listOf(ArrayList(), ArrayList(), ArrayList()) //Accelerometer,Gyro, Fusion

    val accelerometer: Accelerometer = imu.getModule(Accelerometer::class.java)
    val gyroscope: Gyro = imu.getModule(Gyro::class.java)

    var sensorData: File? = startNewCsvFile();

    override fun toString(): String {




        val addon =  "$isConnected, $batteryLevel, $rssi, $isStreaming, $sensorData, $accelerometer, $gyroscope"



        return super.toString() + " " + addon
    }

    @SuppressLint("SuspiciousIndentation")
    fun updateBatRssi(){
        CoroutineScope(Dispatchers.IO).launch {
            while(isConnected) {

                batteryLevel = imu.readBatteryLevelAsync().await().toString()

                delay(10000)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            while(isConnected){

            rssi = imu.readRssiAsync().await().toString()
                delay(1000)
                }
        }
    }

    fun startNewCsvFile(): File {
        val headers = listOf(" aVx"," aVy"," aVz"," aVTime", " ax", " ay", " az", " aTime")

        val filename = "${sensorName.replace(" ", "")}data_${System.currentTimeMillis()}.csv"

        val directory = sessionFolder

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

}




fun collectData(sensor: Sensor) {
    val accelerometer = sensor.accelerometer
    val gyroscope = sensor.gyroscope
    if (!sensor.isConnected) {
        logToFile(
            "MainActivity: ERROR - Sensor not connected, cannot stream data"

        )
    }else if (!sensor.isStreaming) {
        accelerometer.acceleration()?.stop()
        accelerometer.stop()
        gyroscope.angularVelocity()?.stop()
        gyroscope.stop()
        writeData(sensor)
        return
    }
    CoroutineScope(Dispatchers.IO).launch {


        accelerometer.configure()
            .odr(100f)
            .range(4f)
            .commit()
        gyroscope.configure()
            .odr(Gyro.OutputDataRate.ODR_100_HZ)
            .range(Gyro.Range.FSR_2000)
            .commit()


            gyroscope.angularVelocity()?.addRouteAsync { source ->
                source.stream{data, env -> sensor.data[1].add(data)}

            }?.continueWith { task ->
                if (task.isFaulted) {
                    logToFile("MainActivity: ERROR - Failed to configure gyroscope route - ${task.error}")
                } else {

                    logToFile("MainActivity: Gyroscope route configured for ${sensor.sensorName}, ${System.currentTimeMillis()}")
                        if (sensor.isStreaming) {
                            gyroscope.angularVelocity()?.start()
                            gyroscope.start()
                        }
                }
            }



        accelerometer.packedAcceleration()?.addRouteAsync { source ->
            source.stream { data, env ->
                sensor.data[0].add(data)
            }
        }?.continueWith { task ->
            if (task.isFaulted) {
                logToFile(
                    "MainActivity: ERROR - Failed to configure accelerometer route - ${task.error}",
                )
            } else {

                logToFile(
                    "MainActivity: Accelerometer route configured for ${sensor.sensorName}, ${System.currentTimeMillis()}",

                    )

                if (sensor.isStreaming) {
                    accelerometer.acceleration()?.start()
                    accelerometer.start()
                }
                }
            }
        }


    }




suspend fun connect(board: MetaWearBoard, idx: Int = 0) {
    Log.i("Board", "Connecting to ${board.macAddress}")

    val sensorName = "Sensor ${idx + 1}"

    try {
        board.connectAsync().await() // Wait for connection to complete
        logToFile("MainActivity: $sensorName Connected")

        val sensor = Sensor(
            board.macAddress,
            sensorName,
            board
        )

        Log.i("Board", sensor.toString())

        addSensor(sensor)

        sensor.isConnected = true
        //sensor.updateBatRssi()
    } catch (e: Exception) {
        logToFile("MainActivity: Failed to connect $sensorName - ${e.localizedMessage}")
        connect(board, idx)
    }
}




suspend fun <T> Task<T>.await(): T {
    return suspendCoroutine { continuation ->
        this.continueWith { task ->
            if (task.isFaulted) {
                continuation.resumeWithException(task.error)
            } else {
                continuation.resume(task.result as T)
            }
        }
    }
}
fun disconnect(sensor: Sensor){
    sensor.imu.disconnectAsync().continueWith { task ->
        if (task.isFaulted) {
            logToFile("MainActivity: Failed to disconnect ${sensor.sensorName}")
        } else {
            logToFile("MainActivity: ${sensor.sensorName} Disconnected")

            sensor.isConnected = false
            sensor.rssi = "--"
            sensor.batteryLevel = "--"
        }
    }
}

fun writeData(sensor: Sensor) {
    var fileOutputStream = FileOutputStream(sensor.sensorData, true)
    var fileSize = 0
//

    if(sensor.data[0].size <= sensor.data[1].size){
        fileSize = sensor.data[0].size
    }else{
        fileSize = sensor.data[1].size
    }



    for (i in 0..fileSize-1) {
        var gyroData = sensor.data[1][i].value(AngularVelocity::class.java)
        var accData = sensor.data[0][i].value(Acceleration::class.java)

        var aVx = gyroData.x().toString()
        var aVy = gyroData.y().toString()
        var aVz = gyroData.z().toString()
        var x = accData.x().toString()
        var y = accData.y().toString()
        var z = accData.z().toString()


        var angDataString = "$aVx, $aVy, $aVz, ${sensor.data[1][i].timestamp().timeInMillis}, "
        var accDataString = "$x, $y, $z" + ", " + "${sensor.data[0][i].timestamp().timeInMillis} \n"
        var dataString = angDataString+ accDataString
        fileOutputStream.write(dataString.toByteArray())
    }


}



