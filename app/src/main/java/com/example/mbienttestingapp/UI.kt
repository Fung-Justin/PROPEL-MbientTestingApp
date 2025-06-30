package com.example.mbienttestingapp

import android.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(viewModel: SensorViewModel) {
    val sensors by sensorList.collectAsState()
    val allSensorsStreaming by viewModel.allSensorsStreaming.collectAsState()
    val syncMode by viewModel.syncMode.collectAsState()
    val streamingMode by viewModel.streamingMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mbient Testing App") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Sync Mode Selection
            SyncModeCard(
                currentMode = syncMode,
                isStreaming = allSensorsStreaming,
                streamingMode = streamingMode,
                onModeChange = { newMode ->
                    viewModel.switchSyncMode(newMode)
                },
                onStreamingModeChange = { newStreamingMode ->
                    viewModel.switchStreamingMode(newStreamingMode)
                }
            )

            // Sensor List
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = sensors.values.toList(), key = { it.macAddress }) { sensor ->
                    SensorCard(
                        sensor = sensor,
                        onDisconnect = {
                            viewModel.disconnectSensor(sensor)
                        }
                    )
                }
            }

            // Stream Control
            StreamControlCard(
                sensors = sensors.values.toList(),
                isStreaming = allSensorsStreaming,
                syncMode = syncMode,
                onStartStop = {
                    if (allSensorsStreaming) {
                        viewModel.stopSynchronizedCollection()
                    } else {
                        viewModel.startSynchronizedCollection()
                    }
                }
            )
        }
    }
}

@Composable
fun SyncModeCard(
    currentMode: SyncMode,
    isStreaming: Boolean,
    streamingMode: StreamingMode,
    onModeChange: (SyncMode) -> Unit,
    onStreamingModeChange: (StreamingMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Synchronization Mode",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SyncMode.entries.forEach { mode ->
                    FilterChip(
                        colors = if (currentMode == mode) FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color.Green
                        ) else FilterChipDefaults.filterChipColors(),
                        selected = currentMode == mode,
                        onClick = {
                            if (!isStreaming) {
                                onModeChange(mode)
                            }
                        },
                        enabled = !isStreaming,
                        label = { Text(mode.name) },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                }
            }

            // Show streaming options only when in Streaming mode
            if (currentMode == SyncMode.STREAMING) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Streaming Type",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StreamingMode.entries.forEach { mode ->
                        FilterChip(
                            colors = if (streamingMode == mode) FilterChipDefaults.filterChipColors(selectedContainerColor = Color.Green)
                                     else FilterChipDefaults.filterChipColors(),
                            selected = streamingMode == mode,
                            onClick = {
                                if (!isStreaming) {
                                    onStreamingModeChange(mode)
                                }
                            },
                            enabled = !isStreaming,
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(mode.name, fontSize = 12.sp)
                                    Text(
                                        when (mode) {
                                            StreamingMode.PACKED -> "High Speed"
                                            StreamingMode.ACCOUNTED -> "Accurate Time"
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                        )
                    }
                }

                // Info text
                Text(
                    text = when (streamingMode) {
                        StreamingMode.PACKED -> "Higher data rate (~100Hz stable)\nLess accurate timestamps"
                        StreamingMode.ACCOUNTED -> "Precise timestamps\nLower data rate (~66Hz max)"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (isStreaming) {
                Text(
                    text = "Cannot change mode while streaming",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun SensorCard(
    sensor: Sensor,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (sensor.isConnected)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sensor.sensorName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (sensor.isConnected) Icons.Default.Check else Icons.Default.Clear,
                    contentDescription = if (sensor.isConnected) "Connected" else "Disconnected",
                    tint = if (sensor.isConnected) Color.Green else Color.Red
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Battery: ${sensor.batteryLevel}%",
                        fontSize = 14.sp
                    )
                    Text(
                        text = "RSSI: ${sensor.rssi} dBm",
                        fontSize = 14.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Mode: ${sensor.syncMode.name}",
                        fontSize = 14.sp
                    )
                    if (sensor.isStreaming) {
                        Text(
                            text = "● Recording",
                            fontSize = 14.sp,
                            color = Color.Red
                        )
                    }
                }
            }

            // Gyroscope status
            if (!sensor.hasGyroscope) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "No gyroscope",
                        tint = Color(0xFFFFA500), // Orange
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "No gyroscope detected",
                        fontSize = 12.sp,
                        color = Color(0xFFFFA500)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // MAC address info
            Text(
                text = "MAC: ${sensor.macAddress}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Disconnect button
            Button(
                onClick = onDisconnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = sensor.isConnected && !sensor.isStreaming,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
fun StreamControlCard(
    sensors: List<Sensor>,
    isStreaming: Boolean,
    syncMode: SyncMode,
    onStartStop: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isStreaming) "Data Collection Active" else "Ready to Collect Data",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Status indicators
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusChip(
                    label = "Connected",
                    value = "${sensors.count { it.isConnected }}/${sensors.size}",
                    isGood = sensors.all { it.isConnected }
                )
                StatusChip(
                    label = "Mode",
                    value = syncMode.name,
                    isGood = true
                )
                StatusChip(
                    label = "Gyroscopes",
                    value = "${sensors.count { it.hasGyroscope }}/${sensors.size}",
                    isGood = sensors.all { it.hasGyroscope }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Start/Stop button
            Button(
                onClick = {
                    if (!isStreaming && sensors.any { !it.isConnected }) {
                        showDialog = true
                    } else {
                        onStartStop()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color.Red else Color.Green
                )
            ) {
                Text(
                    text = if (isStreaming) {
                        when (syncMode) {
                            SyncMode.STREAMING -> "Stop Streaming"
                            SyncMode.LOGGING -> "Stop Logging"
                        }
                    } else {
                        when (syncMode) {
                            SyncMode.STREAMING -> "Start Streaming"
                            SyncMode.LOGGING -> "Start Logging"
                        }
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isStreaming && syncMode == SyncMode.STREAMING) {
                Text(
                    text = "Data is being saved to CSV files",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (isStreaming && syncMode == SyncMode.LOGGING) {
                Text(
                    text = "Data is being logged on device",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (showDialog) {
        NotAllConnectedDialog(
            sensors = sensors,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun StatusChip(
    label: String,
    value: String,
    isGood: Boolean
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isGood)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$label: ",
                fontSize = 14.sp
            )
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun NotAllConnectedDialog(
    sensors: List<Sensor>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Sensors Not Connected",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column {
                Text("The following sensors are not connected:")
                Spacer(modifier = Modifier.height(8.dp))
                sensors.filter { !it.isConnected }.forEach { sensor ->
                    Text(
                        text = "• ${sensor.sensorName}",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                val sensorsWithoutGyro = sensors.filter { !it.hasGyroscope }
                if (sensorsWithoutGyro.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("The following sensors don't have gyroscopes:")
                    sensorsWithoutGyro.forEach { sensor ->
                        Text(
                            text = "• ${sensor.sensorName}",
                            color = Color(0xFFFFA500) // Orange
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}