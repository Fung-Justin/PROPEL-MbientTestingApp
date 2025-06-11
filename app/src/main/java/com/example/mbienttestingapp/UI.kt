package com.example.mbienttestingapp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults.topAppBarColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(){

    val sensors by sensorList.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            Topbar()
        }
    ) { paddingValues ->

        Column(
            Modifier.fillMaxWidth().padding(paddingValues)
        ) {
            LazyColumn {
                items(items = sensors.values.toList(), key = { it.macAddress }) {sensor ->
                    SensorCard(
                        sensor = sensor,
                        onConnectToggle = {
                            disconnect(sensor)
                        }
                    )
                }
            }

            streamToggle(sensors.values.toList())
        }
    }
}

@Composable
fun streamToggle(sensorList: List<Sensor>){

    var showDialog by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column (horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(15.dp)){
            Text(text = "Stream Data", modifier = Modifier.padding(bottom = 8.dp))
            Switch(
                modifier = Modifier
                    .height(45.dp),
                checked = sensorList.all { it.isStreaming },
                onCheckedChange = {
                    sensorList.forEach { sensor -> sensor.isStreaming = !sensor.isStreaming }

                    if (sensorList.any { it.isStreaming } && !sensorList.any { it.isConnected }) {
                        showDialog = true
                        sensorList.forEach { sensor -> sensor.isStreaming = false }
                    } else {
                        collectData(sensorList[0])
                        collectData(sensorList[1])
                    }
                }

            )
        }
    }

    if (showDialog) {
        AlertDialog("A sensor is not connected", onDismiss = { showDialog = false })
    }
}

@Composable
fun SensorCard(
    sensor: Sensor,
    onConnectToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }


    Card(modifier = modifier.padding(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if(sensor.isConnected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer,
        )
    ) {
        Row(Modifier.padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 8.dp)) {
            Text(
                text = sensor.sensorName,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if(sensor.isConnected) Icons.Filled.Check else Icons.Filled.Clear , // Use the plus icon
                contentDescription = "Add", // Important for accessibility
                modifier = Modifier.height(30.dp)

            )
        }
        Text( text = "Battery Level: ${sensor.batteryLevel} %",
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 17.dp, start = 15.dp, end = 15.dp, bottom = 15.dp)
        )
        Text( text = "RSSI: ${sensor.rssi} dBm",
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp)
        )
        Button(
            onClick = onConnectToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!sensor.isConnected) Color.Red else Color.Green,
                contentColor = Color.White
            ),
            modifier = Modifier
                .padding(start = 15.dp, end = 15.dp, bottom = 15.dp)
                .fillMaxWidth()
                .height(45.dp)
        ) {
            Text(text = if (sensor.isConnected) "Connected" else "Disconnected")
        }

//        Text(text = "Stream Data", modifier = Modifier.padding(start = 15.dp, end = 15.dp))
//        Switch(
//            modifier = Modifier
//                .padding(start = 15.dp, end = 15.dp, bottom = 15.dp)
//                .height(45.dp),
//            checked = sensor.isStreaming, // The current state of the switch (on/off)
//            onCheckedChange = {
//                sensor.isStreaming = it
//                // Update the state when the switch is toggled
//                if (sensor.isStreaming && !sensor.isConnected) {
//                    showDialog = true
//                    sensor.isStreaming = false
//                }else{
//                    collectData(sensor)
//                }
//            }
//
//        )
    }
    if (showDialog) {
        AlertDialog("${sensor.sensorName} is not connected", onDismiss = { showDialog = false })
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Topbar() {

    TopAppBar(
        colors = topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text("Mbient Testing App")


        }
    )
}

@Composable
fun AlertDialog(dialogText: String, onDismiss: () -> Unit) {
    var showDialog by remember { mutableStateOf(true) }

    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showDialog = false
                onDismiss()
            },
            title = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(text = dialogText, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                }
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(onClick = {
                        showDialog = false
                        onDismiss()
                    }) {
                        Text("Close")
                    }
                }
            }
        )
    }
}

