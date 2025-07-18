package com.example.mbienttestingapp

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.android.BtleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class SensorViewModel(application: Application) : AndroidViewModel(application), ServiceConnection {

    private var serviceBinder: BtleService.LocalBinder? = null

    // UI State
    private val _allSensorsStreaming = MutableStateFlow(false)
    val allSensorsStreaming: StateFlow<Boolean> = _allSensorsStreaming.asStateFlow()

    private val _syncMode = MutableStateFlow(SyncMode.STREAMING)
    val syncMode: StateFlow<SyncMode> = _syncMode.asStateFlow()

    private val _streamingMode = MutableStateFlow(StreamingMode.PACKED)
    val streamingMode: StateFlow<StreamingMode> = _streamingMode.asStateFlow()

    // Session management
    var sessionFolder: File? = null
        private set
    var logFile: File? = null
        private set

    init {
        initializeSession()
        bindService()
    }

    private fun initializeSession() {
        val sessionTime = System.currentTimeMillis()

        val externalDir = getApplication<Application>().getExternalFilesDir(null)
        sessionFolder = File(externalDir, "MbientData/${sessionTime}_session")
        sessionFolder?.mkdirs()

        logFile = File(sessionFolder, "Logs_${sessionTime}.txt")
        logToFile("SensorViewModel: Session initialized at $sessionTime")
        logToFile("SensorViewModel: Data will be saved to: ${sessionFolder?.absolutePath}")

    }

    private fun bindService() {
        getApplication<Application>().bindService(
            Intent(getApplication(), BtleService::class.java),
            this,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        serviceBinder = service as BtleService.LocalBinder
        logToFile("SensorViewModel: BtleService connected")

        // Start connecting to boards
        viewModelScope.launch {
            connectToBoards()
        }
    }

    override fun onServiceDisconnected(componentName: ComponentName) {
        serviceBinder = null
        logToFile("SensorViewModel: BtleService disconnected")
    }

    private fun connectToBoards() {
        val btManager = getApplication<Application>().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        if (serviceBinder == null) {
            logToFile("SensorViewModel: ERROR - Service not bound")
            return
        }

        // Connect to each sensor
        macAddress.forEachIndexed { idx, address ->
            viewModelScope.launch {
                try {
                    val remoteDevice = btManager.adapter.getRemoteDevice(address)
                    val board = serviceBinder?.getMetaWearBoard(remoteDevice)

                    if (board != null) {
                        logToFile("SensorViewModel: Retrieved board for $address")
                        connectToSensor(board, idx)
                    } else {
                        logToFile("SensorViewModel: ERROR - Failed to get board for $address")
                    }
                } catch (e: Exception) {
                    logToFile("SensorViewModel: ERROR - Exception getting board for $address: ${e.message}")
                }
            }
        }
    }

    private suspend fun connectToSensor(board: MetaWearBoard, idx: Int) {
        val sensorName = "Sensor ${idx + 1}"
        var retryCount = 0
        val maxRetries = 3

        while (retryCount < maxRetries) {
            try {
                logToFile("$sensorName: Connection attempt ${retryCount + 1}")

                // Connect to the board
                board.connectAsync().await()

                logToFile("$sensorName: Connected successfully")

                // Create sensor object
                val sensor = Sensor(
                    macAddress = board.macAddress,
                    sensorName = sensorName,
                    imu = board
                )

                // Add to sensor list
                addSensor(sensor)
                sensor.isConnected = true

                // Optimize BLE connection for streaming
                sensor.optimizeConnection()

                // Setup initial mode
                when (_syncMode.value) {
                    SyncMode.STREAMING -> sensor.setupStreamingMode()
                    SyncMode.LOGGING -> sensor.setupLoggingMode()
                }

                // Start monitoring battery and RSSI
                sensor.startMonitoring(viewModelScope)

                logToFile("$sensorName: Setup completed")
                return

            } catch (e: Exception) {
                retryCount++
                logToFile("$sensorName: Connection failed (attempt $retryCount) - ${e.message}")

                if (retryCount < maxRetries) {
                    delay(1000L * retryCount)
                } else {
                    logToFile("$sensorName: ERROR - Max retries reached, giving up")
                }
            }
        }
    }

    fun switchStreamingMode(newMode: StreamingMode) {
        if (_allSensorsStreaming.value) {
            logToFile("SensorViewModel: Cannot change streaming mode while streaming")
            return
        }

        _streamingMode.value = newMode

        // Update all connected sensors
        viewModelScope.launch {
            sensorList.value.values.forEach { sensor ->
                if (sensor.isConnected) {
                    sensor.streamingMode = newMode
                    logToFile("${sensor.sensorName}: Streaming mode set to ${newMode.name}")

                    // If currently in streaming mode, reconfigure
                    if (sensor.syncMode == SyncMode.STREAMING) {
                        sensor.cleanup()
                        delay(200)
                        sensor.setupStreamingMode()
                    }
                }
            }
            logToFile("SensorViewModel: All sensors updated to ${newMode.name} streaming")
        }
    }

    fun startSynchronizedCollection() {
        viewModelScope.launch {
            val sensors = sensorList.value.values.toList()

            if (sensors.any { !it.isConnected }) {
                logToFile("SensorViewModel: ERROR - Not all sensors connected")
                return@launch
            }

            // Check if all sensors have gyroscopes if needed
            val sensorsWithoutGyro = sensors.filter { !it.hasGyroscope }
            if (sensorsWithoutGyro.isNotEmpty()) {
                logToFile("SensorViewModel: WARNING - The following sensors don't have gyroscopes: ${sensorsWithoutGyro.map { it.sensorName }}")
            }

            logToFile("SensorViewModel: Starting synchronized ${_syncMode.value.name} collection")

            when (_syncMode.value) {
                SyncMode.STREAMING -> startSynchronizedStreaming(sensors)
                SyncMode.LOGGING -> startSynchronizedLogging(sensors)
            }
        }
    }

    fun stopSynchronizedCollection() {
        viewModelScope.launch {
            val sensors = sensorList.value.values.toList()

            logToFile("SensorViewModel: Stopping synchronized collection")

            sensors.forEach { sensor ->
                sensor.stopDataCollection()
            }

            // For logging mode, download the data
            if (_syncMode.value == SyncMode.LOGGING) {
                sensors.forEach { sensor ->
                    launch {
                        sensor.downloadLoggedData()
                    }
                }
            }

            _allSensorsStreaming.value = false
        }
    }

    private suspend fun startSynchronizedStreaming(sensors: List<Sensor>) {
        // Ensure all sensors are in streaming mode
        coroutineScope {
            val setupTasks = sensors.map { sensor ->
                async {
                    sensor.syncMode = SyncMode.STREAMING
                    sensor.streamingMode = _streamingMode.value
                    sensor.setupStreamingMode()
                }
            }

            // Wait for all setups to complete
            setupTasks.awaitAll()
        }

        // Start all sensors simultaneously
        sensors.forEach { sensor ->
            sensor.startDataCollection()
        }

        _allSensorsStreaming.value = true

        // Start periodic data writing
        viewModelScope.launch {
            while (_allSensorsStreaming.value) {
                delay(5000) // Write data every 5 seconds
                sensors.forEach { sensor ->
                    sensor.writeStreamingData()
                }
            }
        }

        logToFile("SensorViewModel: All sensors started streaming")
    }

    private suspend fun startSynchronizedLogging(sensors: List<Sensor>) {
        // Ensure all sensors are in logging mode
        coroutineScope {
            val setupTasks = sensors.map { sensor ->
                async {
                    sensor.syncMode = SyncMode.LOGGING
                    sensor.setupLoggingMode()
                }
            }

            // Wait for all setups to complete
            setupTasks.awaitAll()
        }

        // Start all sensors simultaneously
        sensors.forEach { sensor ->
            sensor.startDataCollection()
        }

        _allSensorsStreaming.value = true
        logToFile("SensorViewModel: All sensors started logging")
    }

    fun switchSyncMode(newMode: SyncMode) {
        if (_allSensorsStreaming.value) {
            logToFile("SensorViewModel: Cannot switch mode while streaming")
            return
        }

        _syncMode.value = newMode

        viewModelScope.launch {
            // Clean up existing routes
            sensorList.value.values.forEach { sensor ->
                if (sensor.isConnected) {
                    try {
                        sensor.cleanup()
                        delay(200)
                    } catch (e: Exception) {
                        logToFile("${sensor.sensorName}: Cleanup error - ${e.message}")
                    }
                }
            }

            // Setup new mode
            sensorList.value.values.forEach { sensor ->
                if (sensor.isConnected) {
                    launch {
                        sensor.syncMode = newMode

                        val success = when (newMode) {
                            SyncMode.STREAMING -> sensor.setupStreamingMode()
                            SyncMode.LOGGING -> sensor.setupLoggingMode()
                        }

                        if (success) {
                            logToFile("${sensor.sensorName}: Switched to ${newMode.name} mode")
                        } else {
                            logToFile("${sensor.sensorName}: ERROR - Failed to switch to ${newMode.name}")
                        }
                    }
                }
            }

            delay(1000)
            logToFile("SensorViewModel: Mode switch to ${newMode.name} completed")
        }
    }

    fun disconnectSensor(sensor: Sensor) {
        viewModelScope.launch {
            try {
                if (sensor.isStreaming) {
                    sensor.stopDataCollection()
                }

                sensor.cleanup()
                sensor.imu.disconnectAsync().await()

                sensor.isConnected = false
                sensor.rssi = "-"
                sensor.batteryLevel = "-"

                logToFile("${sensor.sensorName}: Disconnected successfully")
            } catch (e: Exception) {
                logToFile("${sensor.sensorName}: ERROR - Failed to disconnect: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()

        // Disconnect all sensors
        sensorList.value.values.forEach { sensor ->
            if (sensor.isConnected) {
                sensor.cleanup()
                sensor.imu.disconnectAsync()
            }
        }

        // Unbind service
        try {
            getApplication<Application>().unbindService(this)
        } catch (e: Exception) {
            logToFile("SensorViewModel: Error unbinding service - ${e.message}")
        }

        logToFile("SensorViewModel: Cleaned up")
    }

    // Make session folder accessible for global logging
    companion object {
        private var instance: SensorViewModel? = null

        fun getInstance(): SensorViewModel? = instance

        fun setInstance(viewModel: SensorViewModel) {
            instance = viewModel
        }

    }
}

// Updated global logging function
fun logToFile(message: String) {
    try {
        val time = System.currentTimeMillis()
        val logMessage = "$time - $message\n"

        // Try to get log file from ViewModel first
        val logFile = SensorViewModel.getInstance()?.logFile ?: MainActivity.logFile // Fallback for early initialization

        logFile?.appendText(logMessage)
        Log.d("MbientApp", message)
    } catch (e: Exception) {
        Log.e("MbientApp", "Failed to write to log file: ${e.message}")
    }
}