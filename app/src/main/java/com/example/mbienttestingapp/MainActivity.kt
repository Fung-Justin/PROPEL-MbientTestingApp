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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.mbienttestingapp.ui.theme.MbientTestingAppTheme
import com.mbientlab.metawear.MetaWearBoard
import com.mbientlab.metawear.android.BtleService
import com.mbientlab.metawear.android.BtleService.LocalBinder
import bolts.Task
import com.mbientlab.metawear.AsyncDataProducer
import kotlinx.coroutines.delay

import java.util.TimerTask


class MainActivity : ComponentActivity(), ServiceConnection {
    private var serviceBinder: LocalBinder? = null
    private val sensor1Mac: String = "E9:9B:AA:92:6A:F5"
    private val sensor2Mac: String = "E2:81:A5:DD:A7:DB"

    private var sensor1: MetaWearBoard? = null
    private var sensor2: MetaWearBoard? = null





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind to the BtleService
        applicationContext.bindService(
            Intent(this, BtleService::class.java),
            this, Context.BIND_AUTO_CREATE
        )

        fun retrieveBoard(macAddress: String): MetaWearBoard? {
            Log.i("retrieveBoard","Retrieving....")
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val remoteDevice = btManager.adapter.getRemoteDevice(macAddress)
            Log.i("retrieveBoard","Retrieved")
            return serviceBinder?.getMetaWearBoard(remoteDevice)?.also { board ->
                if (macAddress == sensor1Mac) {
                    sensor1 = board
                } else if (macAddress == sensor2Mac) {
                    sensor2 = board
                }
            }
        }


        enableEdgeToEdge()
        setContent {
            var isSensor1Connected by remember { mutableStateOf(false) }
            var isSensor2Connected by remember {mutableStateOf(false)}
            var sensor1BatteryLevel by remember { mutableStateOf("--") }
            var sensor2BatteryLevel by remember { mutableStateOf("--") }
            var sensor1Rssi by remember { mutableStateOf("--") }
            var sensor2Rssi by remember { mutableStateOf("--") }


            LaunchedEffect(isSensor1Connected) {
                while (isSensor1Connected) {
                    sensor1?.readRssiAsync()?.continueWith { task ->
                        sensor1Rssi = task.result.toString()
                    }
                    delay(2000)
                }
            }


            MbientTestingAppTheme {
                Column {
                    Topbar("Mbient Testing App")
                    SensorControl(
                        sensorName = "Sensor 1",
                        batteryLevel = sensor1BatteryLevel,
                        rssi = sensor1Rssi,
                        isConnected = isSensor1Connected,
                        onConnectToggle = {
                            if(!isSensor1Connected) {
                                val board = retrieveBoard(macAddress = sensor1Mac)
                                board?.connectAsync()?.continueWith { task ->
                                    if (task.isFaulted) {
                                        Log.i("MainActivity", "Failed to connect")
                                    } else {
                                        Log.i("MainActivity", "Connected")
                                        isSensor1Connected = true
                                        sensor1?.readBatteryLevelAsync()?.continueWith { task -> sensor1BatteryLevel = task.result.toString()}
                                    }
                                    null
                                }
                            }else{
                                sensor1?.disconnectAsync()?.continueWith { task ->
                                    if (task.isFaulted) {
                                        Log.i("MainActivity", "Failed to disconnect")
                                    } else {
                                        Log.i("MainActivity", "Disconnected")
                                        isSensor1Connected = false
                                        sensor1Rssi = "--"
                                        sensor1BatteryLevel = "--"
                                    }
                                }
                            }
                        })
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



@Composable
fun SensorControl(
    sensorName: String,
    isConnected: Boolean, // Represents the current connection state
    onConnectToggle: () -> Unit, // Lambda to handle the button click
    batteryLevel: String,
    rssi: String,
    modifier: Modifier = Modifier // Optional modifier for the Column

) {

    Card(modifier = modifier.padding(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if(isConnected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer,
    )
    ) {
        Row(Modifier.padding(start = 15.dp, end = 15.dp, top = 15.dp, bottom = 8.dp)) {
            Text(
                text = sensorName,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if(isConnected) Icons.Filled.Check else Icons.Filled.Clear , // Use the plus icon
                contentDescription = "Add", // Important for accessibility
                modifier = Modifier.height(30.dp)

            )
        }
        Text( text = "Battery Level: $batteryLevel %",
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 17.dp, start = 15.dp, end = 15.dp, bottom = 15.dp)
            )
        Text( text = "RSSI: $rssi dBm",
            fontSize = 16.sp,
            modifier = Modifier.padding(start = 15.dp, end = 15.dp, bottom = 15.dp)
        )
        Button(
            onClick = onConnectToggle,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (!isConnected) Color.Red else Color.Green,
                contentColor = Color.White
            ),
            modifier = Modifier
                .padding(start = 15.dp, end = 15.dp, bottom = 15.dp)
                .fillMaxWidth()
                .height(45.dp)
        ) {
            Text(text = if (isConnected) "Disconnect" else "Connect")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Topbar(title: String) {

    TopAppBar(
        colors = topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            titleContentColor = MaterialTheme.colorScheme.primary,
        ),
        title = {
            Text(title)
        }
    )

}



@Preview(showBackground = true)
@Composable
fun Preview() {

}


