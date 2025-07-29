package com.example.mbienttestingapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import bolts.Task
import com.mbientlab.metawear.Data
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.Route
import com.mbientlab.metawear.data.Acceleration
import com.mbientlab.metawear.data.AngularVelocity
import com.mbientlab.metawear.module.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// MAC addresses of your sensors
val macAddress: List<String> = listOf(
    "E9:9B:AA:92:6A:F5", // Sensor 1
    "E2:81:A5:DD:A7:DB"  // Sensor 2
)

// Global sensor list management
private val _sensorList = MutableStateFlow<Map<String, Sensor>>(emptyMap())
val sensorList: StateFlow<Map<String, Sensor>> = _sensorList

fun addSensor(sensor: Sensor) {
    _sensorList.update { currentMap ->
        currentMap + (sensor.macAddress to sensor)
    }
}

enum class StreamingMode {
    PACKED,      // Higher throughput, less accurate timestamps
    ACCOUNTED    // Lower throughput, accurate timestamps
}

enum class SyncMode {
    STREAMING,  // Real-time streaming with timestamp alignment
    LOGGING     // On-board logging for perfect synchronization
}

class Sensor(
    val macAddress: String,
    val sensorName: String = "",
    val imu: MetaWearBoard
) {
    // UI state
    var isConnected by mutableStateOf(false)
    var isStreaming by mutableStateOf(false)
    var batteryLevel by mutableStateOf("-")
    var rssi by mutableStateOf("-")
    var syncMode by mutableStateOf(SyncMode.STREAMING)
    var streamingMode by mutableStateOf(StreamingMode.PACKED)
    private var isDownloading = false

    // Modules
    val accelerometer: Accelerometer = imu.getModule(Accelerometer::class.java)
    private val gyroscopeBmi160: GyroBmi160? = imu.getModule(GyroBmi160::class.java)
    private val gyroscopeBmi270: GyroBmi270? = imu.getModule(GyroBmi270::class.java)
    val gyro: Gyro? = imu.getModule(Gyro::class.java)
    val logging: Logging = imu.getModule(Logging::class.java)
    val settings: Settings = imu.getModule(Settings::class.java)

    val hasGyroscope: Boolean = gyroscopeBmi160 != null || gyroscopeBmi270 != null

    // Data collections
    val accDataQueue: MutableList<Data> = Collections.synchronizedList(mutableListOf<Data>())
    val gyroDataQueue: MutableList<Data> = Collections.synchronizedList(mutableListOf<Data>())

    // CSV file management
    private var csvFile: File? = null
    private var csvWriter: BufferedWriter? = null

    // Routes
    private var accRoute: Route? = null
    private var gyroRoute: Route? = null

    init {
        logToFile("${sensorName}: Board info - MAC: ${imu.macAddress}, Has Gyro: $hasGyroscope")
    }

    fun optimizeConnection() {
        try {
            settings.editBleConnParams()
                .minConnectionInterval(7.5f)
                .maxConnectionInterval(7.5f)
                .commit()
            logToFile("${sensorName}: BLE connection optimized")
        } catch (e: Exception) {
            logToFile("${sensorName}: Failed to optimize BLE - ${e.message}")
        }
    }

    suspend fun setupStreamingMode(): Boolean {
        return try {
            // Configure sensors
            accelerometer.configure()
                .odr(100f)
                .range(4f)
                .commit()

            gyro?.configure()
                ?.odr(Gyro.OutputDataRate.ODR_100_HZ)
                ?.range(Gyro.Range.FSR_2000)
                ?.commit()

            // Setup routes based on streaming mode
            when (streamingMode) {
                StreamingMode.PACKED -> setupPackedStreaming()
                StreamingMode.ACCOUNTED -> setupAccountedStreaming()
            }

            logToFile("${sensorName}: Streaming mode configured (${streamingMode.name})")
            true
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to setup streaming - ${e.message}")
            false
        }
    }
//
//    private suspend fun setupPackedStreaming() {
//        // Packed data - 3 samples per BLE packet, higher throughput
//        val accTask = accelerometer.packedAcceleration().addRouteAsync { source ->
//            source.stream { data, env ->
//                accDataQueue.add(data)
//            }
//        }
//
//        val gyroTask = when {
//            gyroscopeBmi160 != null -> {
//                gyroscopeBmi160.packedAngularVelocity().addRouteAsync { source ->
//                    source.stream { data, env ->
//                        gyroDataQueue.add(data)
//                    }
//                }
//            }
//            gyroscopeBmi270 != null -> {
//                gyroscopeBmi270.packedAngularVelocity().addRouteAsync { source ->
//                    source.stream { data, env ->
//                        gyroDataQueue.add(data)
//                    }
//                }
//            }
//            else -> null
//        }
//
//        accRoute = accTask.await()
//        gyroRoute = gyroTask?.await()
//
//        logToFile("${sensorName}: Using PACKED streaming (high throughput)")
//    }

//    private suspend fun setupPackedStreaming() {
//
//        val accProducer  = accelerometer.packedAcceleration()
//        val gyroProducer =  gyroscopeBmi270?.packedAngularVelocity()
//
//        val acctask = gyroProducer?.addRouteAsync { source ->
//                    source.buffer().name("gyro-buffer")
//                }?.onSuccessTask {
//                    accProducer.addRouteAsync { source ->
//                        source.fuse("gyro-buffer")
//                            .limit(5)
//                            .stream { data, env ->
//                                var values = data.value(Array<Data>::class.java)
//
//                                accDataQueue.add(values[0])
//                                gyroDataQueue.add(values[1])
//
//                            }
//                    }
//                }
//
//        accRoute = acctask?.await()
//
//        logToFile("$sensorName: Using fused packed streaming")
//    }

    private suspend fun setupPackedStreaming() {
        val accProducer = accelerometer.packedAcceleration()
        var gyroProducer = gyroscopeBmi270?.packedAngularVelocity()

        if (gyroProducer == null) {
            gyroProducer = gyroscopeBmi160?.packedAngularVelocity()
            return
        }

        val gyroTask = gyroProducer.addRouteAsync { source ->
            source.buffer().name("gyro-buffer")
        }.onSuccessTask {
                    accProducer.addRouteAsync { source ->
                        source.fuse("gyro-buffer")
                            .limit(5)
                            .stream { data, env ->
                                var values = data.value(Array<Data>::class.java)

                                accDataQueue.add(values[0])
                                gyroDataQueue.add(values[1])

                            }
                    }
                }


        // Await both tasks
        gyroRoute = gyroTask.await()


        logToFile("$sensorName: Using fused packed streaming")
    }



    private suspend fun setupAccountedStreaming() {
        // Unpacked with accounting - 2 samples per packet, accurate timestamps
        val accTask = accelerometer.acceleration().addRouteAsync { source ->
            source.account().stream { data, env ->
                accDataQueue.add(data)
            }
        }

        val gyroTask = when {
            gyroscopeBmi160 != null -> {
                gyroscopeBmi160.angularVelocity().addRouteAsync { source ->
                    source.account().stream { data, env ->
                        gyroDataQueue.add(data)
                    }
                }
            }
            gyroscopeBmi270 != null -> {
                gyroscopeBmi270.angularVelocity().addRouteAsync { source ->
                    source.account().stream { data, env ->
                        gyroDataQueue.add(data)
                    }
                }
            }
            else -> null
        }

        accRoute = accTask.await()
        gyroRoute = gyroTask?.await()

        logToFile("${sensorName}: Using ACCOUNTED streaming (accurate timestamps)")
    }

    suspend fun setupLoggingMode(): Boolean {
        return try {
            logToFile("${sensorName}: Starting logging setup...")

            //Full teardown to reset board state
            imu.tearDown()
            delay(1000)
            logToFile("${sensorName}: Teardown completed")

            // Re-get modules after teardown
            val newAccelerometer = imu.getModule(Accelerometer::class.java)
            val newGyro = imu.getModule(Gyro::class.java)
            val newLogging = imu.getModule(Logging::class.java)

            // Configure sensors
            newAccelerometer.configure()
                .odr(100f)
                .range(4f)
                .commit()

            delay(200)

            newGyro?.configure()
                ?.odr(Gyro.OutputDataRate.ODR_100_HZ)
                ?.range(Gyro.Range.FSR_2000)
                ?.commit()

            delay(200)

            // Clear logs
            newLogging.clearEntries()
            delay(500)

            // Create logging routes
            val accTask = newAccelerometer.acceleration().addRouteAsync { source ->
                source.log { data, env ->
                    if (isDownloading) {
                        accDataQueue.add(data)
                    }
                }
            }
            accRoute = accTask.await()
            logToFile("${sensorName}: Accelerometer logging route created")

            delay(1000) // Delay between sensor routes

            // Setup gyroscope logging if available
            if (hasGyroscope) {
                when {
                    gyroscopeBmi160 != null -> {
                        val newGyroBmi160 = imu.getModule(GyroBmi160::class.java)
                        val gyroTask = newGyroBmi160.angularVelocity().addRouteAsync { source ->
                            source.log { data, env ->
                                if (isDownloading) {
                                    gyroDataQueue.add(data)
                                }
                            }
                        }
                        gyroRoute = gyroTask.await()
                        logToFile("${sensorName}: Gyroscope logging route created")
                    }
                    gyroscopeBmi270 != null -> {
                        val newGyroBmi270 = imu.getModule(GyroBmi270::class.java)
                        val gyroTask = newGyroBmi270.angularVelocity().addRouteAsync { source ->
                            source.log { data, env ->
                                if (isDownloading) {
                                    gyroDataQueue.add(data)
                                }
                            }
                        }
                        gyroRoute = gyroTask.await()
                        logToFile("${sensorName}: Gyroscope logging route created")
                    }
                }
            }

            logToFile("${sensorName}: Logging mode configured successfully")
            true

        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to setup logging - ${e.message}")
            cleanup()
            false
        }
    }

    fun startDataCollection() {
        when (syncMode) {
            SyncMode.STREAMING -> startStreaming()
            SyncMode.LOGGING -> startLogging()
        }
        isStreaming = true
    }

    fun stopDataCollection() {
        when (syncMode) {
            SyncMode.STREAMING -> stopStreaming()
            SyncMode.LOGGING -> stopLogging()
        }
        isStreaming = false
    }

    private fun startStreaming() {
        try {
            when (streamingMode) {
                StreamingMode.PACKED -> {
                    accelerometer.packedAcceleration().start()
                    accelerometer.start()

                    when {
                        gyroscopeBmi160 != null -> {
                            gyroscopeBmi160.packedAngularVelocity().start()
                            gyroscopeBmi160.start()
                        }
                        gyroscopeBmi270 != null -> {
                            gyroscopeBmi270.packedAngularVelocity().start()
                            gyroscopeBmi270.start()
                        }
                    }
                }
                StreamingMode.ACCOUNTED -> {
                    accelerometer.acceleration().start()
                    accelerometer.start()

                    when {
                        gyroscopeBmi160 != null -> {
                            gyroscopeBmi160.angularVelocity().start()
                            gyroscopeBmi160.start()
                        }

                        gyroscopeBmi270 != null -> {
                            gyroscopeBmi270.angularVelocity().start()
                            gyroscopeBmi270.start()
                        }

                    }
                }
            }

            startCsvFile()
            logToFile("${sensorName}: Started ${streamingMode.name} streaming at ${System.currentTimeMillis()}")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to start streaming - ${e.message}")
        }
    }

    private fun stopStreaming() {
        try {
            accelerometer.stop()

            when (streamingMode) {
                StreamingMode.PACKED -> {
                    accelerometer.packedAcceleration().stop()

                    when {
                        gyroscopeBmi160 != null -> {
                            gyroscopeBmi160.stop()
                            gyroscopeBmi160.packedAngularVelocity().stop()
                        }
                        gyroscopeBmi270 != null -> {
                            gyroscopeBmi270.stop()
                            gyroscopeBmi270.packedAngularVelocity().stop()
                        }
                    }
                }
                StreamingMode.ACCOUNTED -> {
                    accelerometer.acceleration().stop()

                    when {
                        gyroscopeBmi160 != null -> {
                            gyroscopeBmi160.stop()
                            gyroscopeBmi160.angularVelocity().stop()
                        }
                        gyroscopeBmi270 != null -> {
                            gyroscopeBmi270.stop()
                            gyroscopeBmi270.angularVelocity().stop()
                        }
                    }
                }
            }

            writeStreamingData()
            closeCsvFile()
            logToFile("${sensorName}: Stopped streaming")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to stop streaming - ${e.message}")
        }
    }

    private fun startLogging() {
        try {
            logging.start(true)
            accelerometer.acceleration().start()
            accelerometer.start()

            when {
                gyroscopeBmi160 != null -> {
                    gyroscopeBmi160.angularVelocity().start()
                    gyroscopeBmi160.start()
                }
                gyroscopeBmi270 != null -> {
                    gyroscopeBmi270.angularVelocity().start()
                    gyroscopeBmi270.start()
                }
            }

            logToFile("${sensorName}: Started logging")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to start logging - ${e.message}")
        }
    }

    private fun stopLogging() {
        try {
            accelerometer.stop()
            accelerometer.acceleration().stop()

            when {
                gyroscopeBmi160 != null -> {
                    gyroscopeBmi160.stop()
                    gyroscopeBmi160.angularVelocity().stop()
                }
                gyroscopeBmi270 != null -> {
                    gyroscopeBmi270.stop()
                    gyroscopeBmi270.angularVelocity().stop()
                }
            }

            logging.stop()
            logToFile("${sensorName}: Stopped logging")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to stop logging - ${e.message}")
        }
    }

    suspend fun downloadLoggedData(): Boolean {
        return try {
            isDownloading = true
            startCsvFile()

            // Flush page for MMS boards
            try {
                logging.flushPage()
                delay(100)
            } catch (_: Exception) {
                // Not an MMS board
            }

            val downloadTask = logging.downloadAsync(100) { nEntriesLeft, totalEntries ->
                val progress = ((totalEntries - nEntriesLeft) * 100 / totalEntries).toInt()
                logToFile("${sensorName}: Download progress: $progress%")
            }

            downloadTask.await()

            writeStreamingData()
            closeCsvFile()
            isDownloading = false

            logToFile("${sensorName}: Downloaded logged data")
            true
        } catch (e: Exception) {
            isDownloading = false
            logToFile("${sensorName}: ERROR - Failed to download - ${e.message}")
            false
        }
    }

    private fun startCsvFile() {
        val headers = if (hasGyroscope) {
            "timestamp_ms,acc_x,acc_y,acc_z,gyro_x,gyro_y,gyro_z"
        } else {
            "timestamp_ms,acc_x,acc_y,acc_z"
        }

        val sessionFolder = SensorViewModel.getInstance()?.sessionFolder
        val filename = "${sensorName.replace(" ", "")}_${syncMode.name.lowercase()}_${System.currentTimeMillis()}.csv"
        csvFile = File(sessionFolder, filename)
        csvWriter = BufferedWriter(FileWriter(csvFile))
        csvWriter?.write("$headers\n")
        csvWriter?.flush()

        logToFile("${sensorName}: Created CSV: ${csvFile?.name}")
    }

    private fun closeCsvFile() {
        csvWriter?.close()
        csvWriter = null
    }

    fun writeStreamingData() {
        if (accDataQueue.isEmpty() && gyroDataQueue.isEmpty()) return

        val syncWindow = 10L
        val accData = accDataQueue.toList()
        val gyroData = gyroDataQueue.toList()

        val sortedAcc = accData.sortedBy { it.timestamp().timeInMillis }
        val sortedGyro = gyroData.sortedBy { it.timestamp().timeInMillis }

        if (hasGyroscope && sortedGyro.isNotEmpty()) {
            var gyroIndex = 0
            sortedAcc.forEach { accSample ->
                val accTimestamp = accSample.timestamp().timeInMillis
                val acc = accSample.value(Acceleration::class.java)

                var matchingGyro: Data? = null
                while (gyroIndex < sortedGyro.size) {
                    val gyroSample = sortedGyro[gyroIndex]
                    val timeDiff = gyroSample.timestamp().timeInMillis - accTimestamp

                    when {
                        timeDiff < -syncWindow -> gyroIndex++
                        timeDiff > syncWindow -> break
                        else -> {
                            matchingGyro = gyroSample
                            break
                        }
                    }
                }

                if (matchingGyro != null) {
                    val gyro = matchingGyro.value(AngularVelocity::class.java)
                    csvWriter?.write("$accTimestamp,${acc.x()},${acc.y()},${acc.z()},${gyro.x()},${gyro.y()},${gyro.z()}\n")
                }
            }
        } else {
            sortedAcc.forEach { accSample ->
                val accTimestamp = accSample.timestamp().timeInMillis
                val acc = accSample.value(Acceleration::class.java)
                csvWriter?.write("$accTimestamp,${acc.x()},${acc.y()},${acc.z()}\n")
            }
        }

        csvWriter?.flush()
        accDataQueue.clear()
        gyroDataQueue.clear()
    }

    fun startMonitoring(scope: CoroutineScope) {
        scope.launch {
            while (isConnected) {
                try {
                    batteryLevel = imu.readBatteryLevelAsync().await().toString()
                    delay(30000)
                } catch (_: Exception) {
                    batteryLevel = "Error"
                }
            }
        }

        scope.launch {
            while (isConnected) {
                try {
                    rssi = imu.readRssiAsync().await().toString()
                    delay(5000)
                } catch (_: Exception) {
                    rssi = "Error"
                }
            }
        }
    }

    fun cleanup() {
        try {
            if (isStreaming) {
                stopDataCollection()
            }

            accRoute?.remove()
            gyroRoute?.remove()
            accRoute = null
            gyroRoute = null

            closeCsvFile()
            accDataQueue.clear()
            gyroDataQueue.clear()
        } catch (e: Exception) {
            logToFile("${sensorName}: Error during cleanup - ${e.message}")
        }
    }
}

// Extension function for Task await
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