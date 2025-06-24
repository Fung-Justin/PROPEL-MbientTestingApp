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
    private var isDownloading = false

    // Modules - properly typed
    val accelerometer: Accelerometer = imu.getModule(Accelerometer::class.java)
    private val gyroscopeBmi160: GyroBmi160? = imu.getModule(GyroBmi160::class.java)
    private val gyroscopeBmi270: GyroBmi270? = imu.getModule(GyroBmi270::class.java)
    val gyro: Gyro? = imu.getModule(Gyro::class.java) // For configuration only
    val logging: Logging = imu.getModule(Logging::class.java)
    val settings: Settings = imu.getModule(Settings::class.java)

    // Helper property to check if any gyroscope is available
    val hasGyroscope: Boolean = gyroscopeBmi160 != null || gyroscopeBmi270 != null

    // Thread-safe data collections for streaming mode
    val accDataQueue: MutableList<Data> = Collections.synchronizedList(mutableListOf<Data>())
    val gyroDataQueue: MutableList<Data> = Collections.synchronizedList(mutableListOf<Data>())

    // CSV file management
    private var csvFile: File? = null
    private var csvWriter: BufferedWriter? = null

    // Routes for cleanup
    private var accRoute: Route? = null
    private var gyroRoute: Route? = null

    init {
        // Log board information for debugging
        logToFile("${sensorName}: Board info - MAC: ${imu.macAddress}")
        logToFile("${sensorName}: Has BMI160 = ${gyroscopeBmi160 != null}")
        logToFile("${sensorName}: Has BMI270 = ${gyroscopeBmi270 != null}")
        logToFile("${sensorName}: Has generic Gyro = ${gyro != null}")

        if (!hasGyroscope) {
            logToFile("WARNING: No gyroscope module found for $sensorName")
        }
    }

    // Configure BLE connection for optimal streaming
    fun optimizeConnection() {
        try {
            settings.editBleConnParams()
                .minConnectionInterval(7.5f)  // Minimum for high-frequency streaming
                .maxConnectionInterval(7.5f)  // Keep consistent
                .commit()
            logToFile("${sensorName}: BLE connection optimized for streaming")
        } catch (e: Exception) {
            logToFile("${sensorName}: Failed to optimize BLE connection - ${e.message}")
        }
    }

    // Setup streaming mode with timestamp alignment
    suspend fun setupStreamingMode(): Boolean {
        return try {
            // Configure accelerometer
            accelerometer.configure()
                .odr(100f)
                .range(4f)
                .commit()

            // Configure gyroscope using generic interface if available
            gyro?.configure()
                ?.odr(Gyro.OutputDataRate.ODR_100_HZ)
                ?.range(Gyro.Range.FSR_2000)
                ?.commit()

            // Setup accelerometer route
            val accTask = accelerometer.packedAcceleration().addRouteAsync { source ->
                source.account().stream { data, env ->
                    accDataQueue.add(data)
                }
            }

            // Setup gyroscope route based on specific type
            val gyroTask = when {
                gyroscopeBmi160 != null -> {
                    gyroscopeBmi160.packedAngularVelocity().addRouteAsync { source ->
                        source.account().stream { data, env ->
                            gyroDataQueue.add(data)
                        }
                    }
                }
                gyroscopeBmi270 != null -> {
                    gyroscopeBmi270.packedAngularVelocity().addRouteAsync { source ->
                        source.account().stream { data, env ->
                            gyroDataQueue.add(data)
                        }
                    }
                }
                else -> null
            }

            // Wait for routes to be configured
            accRoute = accTask.await()
            gyroRoute = gyroTask?.await()

            logToFile("${sensorName}: Streaming mode configured successfully")
            true
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to setup streaming mode - ${e.message}")
            false
        }
    }

    // Setup logging mode for perfect synchronization
    suspend fun setupLoggingMode(): Boolean {
        return try {
            // Clear any existing logs
            logging.clearEntries()

            // Configure accelerometer
            accelerometer.configure()
                .odr(100f)
                .range(4f)
                .commit()

            // Configure gyroscope using generic interface if available
            gyro?.configure()
                ?.odr(Gyro.OutputDataRate.ODR_100_HZ)
                ?.range(Gyro.Range.FSR_2000)
                ?.commit()

            // Setup logging routes for accelerometer
            val accTask = accelerometer.packedAcceleration().addRouteAsync { source ->
                source.log { data, env ->
                    // During download, this will receive the logged data
                    if (isDownloading) {
                        accDataQueue.add(data)
                    }
                }
            }

            // Setup logging routes for gyroscope based on type
            val gyroTask = when {
                gyroscopeBmi160 != null -> {
                    gyroscopeBmi160.packedAngularVelocity().addRouteAsync { source ->
                        source.log { data, env ->
                            if (isDownloading) {
                                gyroDataQueue.add(data)
                            }
                        }
                    }
                }
                gyroscopeBmi270 != null -> {
                    gyroscopeBmi270.packedAngularVelocity().addRouteAsync { source ->
                        source.log { data, env ->
                            if (isDownloading) {
                                gyroDataQueue.add(data)
                            }
                        }
                    }
                }
                else -> null
            }

            // Wait for routes to be configured
            accRoute = accTask.await()
            gyroRoute = gyroTask?.await()

            logToFile("${sensorName}: Logging mode configured successfully")
            true
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to setup logging mode - ${e.message}")
            false
        }
    }

    // Start data collection based on current mode
    fun startDataCollection() {
        when (syncMode) {
            SyncMode.STREAMING -> startStreaming()
            SyncMode.LOGGING -> startLogging()
        }
        isStreaming = true
    }

    // Stop data collection
    fun stopDataCollection() {
        when (syncMode) {
            SyncMode.STREAMING -> stopStreaming()
            SyncMode.LOGGING -> stopLogging()
        }
        isStreaming = false
    }

    private fun startStreaming() {
        try {
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

            startCsvFile()
            logToFile("${sensorName}: Started streaming at ${System.currentTimeMillis()}")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to start streaming - ${e.message}")
        }
    }

    private fun stopStreaming() {
        try {
            accelerometer.stop()
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

            // Write remaining data
            writeStreamingData()
            closeCsvFile()

            logToFile("${sensorName}: Stopped streaming")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to stop streaming - ${e.message}")
        }
    }

    private fun startLogging() {
        try {
            logging.start(true) // true = overwrite if full
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

            logToFile("${sensorName}: Started logging at ${System.currentTimeMillis()}")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to start logging - ${e.message}")
        }
    }

    private fun stopLogging() {
        try {
            accelerometer.stop()
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

            logging.stop()

            logToFile("${sensorName}: Stopped logging")
        } catch (e: Exception) {
            logToFile("${sensorName}: ERROR - Failed to stop logging - ${e.message}")
        }
    }

    // Download logged data
    suspend fun downloadLoggedData(): Boolean {
        return try {
            isDownloading = true
            startCsvFile()

            // For MMS boards, flush the page first
            // According to docs, need to check if it's MMS and call flushPage
            try {
                // Try to flush page - will fail if not MMS
                logging.flushPage()
                logToFile("${sensorName}: Flushed log page (MMS board)")
            } catch (_: Exception) {
                // Not an MMS board or method not available
                logToFile("${sensorName}: Flush not needed/available")
            }

            val downloadTask = logging.downloadAsync(100) { nEntriesLeft, totalEntries ->
                val progress = ((totalEntries - nEntriesLeft) * 100 / totalEntries).toInt()
                logToFile("${sensorName}: Download progress: $progress%")
            }

            downloadTask.await()

            // Process downloaded data
            writeStreamingData()  // Process any queued data
            closeCsvFile()
            isDownloading = false

            logToFile("${sensorName}: Downloaded and processed logged data")
            true
        } catch (e: Exception) {
            isDownloading = false
            logToFile("${sensorName}: ERROR - Failed to download logged data - ${e.message}")
            false
        }
    }

    // CSV file management
    private fun startCsvFile() {
        val headers = if (hasGyroscope) {
            listOf(
                "timestamp_ms",
                "acc_x", "acc_y", "acc_z",
                "gyro_x", "gyro_y", "gyro_z"
            )
        } else {
            listOf(
                "timestamp_ms",
                "acc_x", "acc_y", "acc_z"
            )
        }

        val sessionFolder = SensorViewModel.getInstance()?.sessionFolder
        val filename = "${sensorName.replace(" ", "")}_${syncMode.name.lowercase()}_${System.currentTimeMillis()}.csv"
        csvFile = File(sessionFolder, filename)
        csvWriter = BufferedWriter(FileWriter(csvFile))
        csvWriter?.write(headers.joinToString(",") + "\n")
        csvWriter?.flush()

        logToFile("${sensorName}: Created CSV file: ${csvFile?.name}")
    }

    private fun closeCsvFile() {
        csvWriter?.close()
        csvWriter = null
    }

    // Write streaming data with timestamp alignment
    fun writeStreamingData() {
        if (accDataQueue.isEmpty() && gyroDataQueue.isEmpty()) return

        val syncWindow = 10L // milliseconds

        // Create copies to avoid concurrent modification
        val accData = accDataQueue.toList()
        val gyroData = gyroDataQueue.toList()

        // Sort by timestamp
        val sortedAcc = accData.sortedBy { it.timestamp().timeInMillis }
        val sortedGyro = gyroData.sortedBy { it.timestamp().timeInMillis }

        if (hasGyroscope && sortedGyro.isNotEmpty()) {
            // Write synchronized acc + gyro data
            var gyroIndex = 0

            sortedAcc.forEach { accSample ->
                val accTimestamp = accSample.timestamp().timeInMillis
                val acc = accSample.value(Acceleration::class.java)

                // Find matching gyro sample within time window
                var matchingGyro: Data? = null
                while (gyroIndex < sortedGyro.size) {
                    val gyroSample = sortedGyro[gyroIndex]
                    val timeDiff = gyroSample.timestamp().timeInMillis - accTimestamp

                    when {
                        timeDiff < -syncWindow -> gyroIndex++ // Gyro sample is too old
                        timeDiff > syncWindow -> break // Gyro sample is too far in future
                        else -> {
                            matchingGyro = gyroSample
                            break
                        }
                    }
                }

                if (matchingGyro != null) {
                    val gyro = matchingGyro.value(AngularVelocity::class.java)

                    val row = listOf(
                        accTimestamp,
                        acc.x(), acc.y(), acc.z(),
                        gyro.x(), gyro.y(), gyro.z()
                    ).joinToString(",")

                    csvWriter?.write(row + "\n")
                }
            }
        } else {
            // Write accelerometer data only
            sortedAcc.forEach { accSample ->
                val accTimestamp = accSample.timestamp().timeInMillis
                val acc = accSample.value(Acceleration::class.java)

                val row = listOf(
                    accTimestamp,
                    acc.x(), acc.y(), acc.z()
                ).joinToString(",")

                csvWriter?.write(row + "\n")
            }
        }

        csvWriter?.flush()

        // Clear processed data
        accDataQueue.clear()
        gyroDataQueue.clear()
    }

    // Battery and RSSI monitoring
    fun startMonitoring(scope: CoroutineScope) {
        scope.launch {
            while (isConnected) {
                try {
                    batteryLevel = imu.readBatteryLevelAsync().await().toString()
                    delay(30000) // Update every 30 seconds
                } catch (_: Exception) {
                    batteryLevel = "Error"
                }
            }
        }

        scope.launch {
            while (isConnected) {
                try {
                    rssi = imu.readRssiAsync().await().toString()
                    delay(5000) // Update every 5 seconds
                } catch (_: Exception) {
                    rssi = "Error"
                }
            }
        }
    }

    // Cleanup
    fun cleanup() {
        try {
            accRoute?.remove()
            gyroRoute?.remove()
            closeCsvFile()
            accDataQueue.clear()
            gyroDataQueue.clear()
        } catch (e: Exception) {
            logToFile("${sensorName}: Error during cleanup - ${e.message}")
        }
    }

    override fun toString(): String {
        return "Sensor($sensorName, connected=$isConnected, streaming=$isStreaming, " +
                "battery=$batteryLevel%, rssi=${rssi}dBm, mode=$syncMode, " +
                "hasGyro=$hasGyroscope)"
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