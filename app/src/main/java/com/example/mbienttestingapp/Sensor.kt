package com.example.mbienttestingapp
import bolts.Task
import com.mbientlab.metawear.module.Accelerometer
import com.mbientlab.metawear.module.Gyro
import com.mbientlab.metawear.module.AccelerometerBosch
import java.io.File
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.data.Acceleration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

import kotlin.coroutines.resumeWithException

//dictionary of sensors


class Sensor(val macAddress: String, val sensorName: String = "") {

    var imu: MetaWearBoard? = null
    var isConnected: Boolean = false
    var isStreaming: Boolean = false
    var batteryLevel: String = "-"
    var rssi: String = "-"
    val accelerometer: Accelerometer? = imu?.getModule(Accelerometer::class.java)
    val gyroscope: Gyro? = imu?.getModule(Gyro::class.java)
    val accelerometerBosch: AccelerometerBosch? = imu?.getModule(AccelerometerBosch::class.java)
    var sensorData: File? = null


    fun toggleAccelerometerRoute(): Boolean {
        if (!isConnected) {
            logToFile(
                "MainActivity: ERROR - Sensor not connected, cannot stream data"
            )
            return false
        }
        val fileOutputStream = FileOutputStream(sensorData, true) // Open in append mode

        accelerometer?.configure()
            ?.odr(100f)
            ?.range(4f)
            ?.commit()


        accelerometer?.packedAcceleration()?.addRouteAsync { source ->
            source.stream { data, env ->
                var ax = data.value(Acceleration::class.java).x().toString()
                var ay = data.value(Acceleration::class.java).y().toString()
                var az = data.value(Acceleration::class.java).z().toString()
                var ang = data.value(Acceleration::class.java)
                val dataString = "$ax,$ay,$az" + "," + System.currentTimeMillis() + "\n"

                fileOutputStream.write(dataString.toByteArray())
//                    logToFile(
//                        "MainActivity: Data received - $dataString"
//                    )
            }
        }?.continueWith { task ->
            if (task.isFaulted) {
                logToFile(
                    "MainActivity: ERROR - Failed to configure accelerometer route - ${task.error}",

                    )
            } else {
                logToFile(
                    "MainActivity: Accelerometer route configured for ${sensorName}",

                    )
                if (isStreaming) {
                    accelerometer.acceleration().start()
                    accelerometer.start()
                } else {
                    accelerometer.acceleration().stop()
                    accelerometer.stop()
                }
            }
            null
        }
        return true
    }
}

//suspend fun <T> com.mbientlab.metawear.Task<T>.await(): T {
//    return suspendCoroutine { continuation ->
//        this.continueWith { task ->
//            if (task.isFaulted) {
//                continuation.resumeWithException(task.error)
//            } else {
//                continuation.resume(task.result as T)
//            }
//            null
//        }
//    }
//}

fun toggleConnect(sensor: Sensor, board: MetaWearBoard) {
    CoroutineScope(Dispatchers.IO).launch {
        if (!sensor.isConnected) {
            sensor.isConnected = true
            try {
                board.connectAsync()?.await()


                val batteryLevelResult = board.readBatteryLevelAsync().await()
                sensor.batteryLevel = batteryLevelResult.toString()
            } catch (e: Exception) {
                logToFile("MainActivity: Failed to connect ${sensor.sensorName} ")
                sensor.isConnected = false
            }
        } else {
            try {
                board.disconnectAsync().await()

                sensor.isConnected = false
                sensor.rssi = "--"
                sensor.batteryLevel = "--"
            } catch (e: Exception) {
                logToFile("MainActivity: Failed to disconnect ${sensor.sensorName}")
            }
        }
    }
}

fun toggleConnect(sensor: Sensor){
    if(!sensor.isConnected) {
        sensor.isConnected = true

        sensor.imu?.connectAsync()?.continueWith { task ->
            if (task.isFaulted) {
                logToFile("MainActivity: Failed to connect ${sensor.sensorName} ")
                sensor.isConnected = false
            } else {
                logToFile("MainActivity: ${sensor.sensorName} Connected")

                sensor.imu?.readBatteryLevelAsync()
                    ?.continueWith { task -> sensor.batteryLevel = task.result.toString() }
            }

            null
        }
    }else {
        sensor.imu?.disconnectAsync()?.continueWith { task ->
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
}

var sensorMap: Map<String, Sensor> = mutableMapOf("Sensor1" to Sensor("E9:9B:AA:92:6A:F5", "Sensor1"), "Sensor2" to Sensor("E2:81:A5:DD:A7:DB", "Sensor2"))
