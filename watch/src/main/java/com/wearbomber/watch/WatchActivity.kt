package com.wearbomber.watch

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

private val GAME_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

class WatchActivity : Activity() {
    private lateinit var gameView: WatchGameView
    private var socket: BluetoothSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        gameView = WatchGameView(this, ::sendCommand, ::connectToPhone)
        setContentView(gameView)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 84)
        }
    }

    private fun connectToPhone() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            gameView.setConnectionStatus("No Bluetooth adapter")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 84)
            gameView.setConnectionStatus("Grant Nearby devices permission")
            return
        }
        thread(name = "WearBomber-RFCOMM-client") {
            try {
                @Suppress("MissingPermission")
                val device = adapter.bondedDevices.firstOrNull { it.name?.contains("WearBomber", true) == true }
                    ?: adapter.bondedDevices.firstOrNull()
                if (device == null) {
                    runOnUiThread { gameView.setConnectionStatus("Pair with phone, then CONNECT") }
                    return@thread
                }
                runOnUiThread { gameView.setConnectionStatus("Connecting to ${device.name ?: "phone"}…") }
                socket = (device as BluetoothDevice).createRfcommSocketToServiceRecord(GAME_UUID)
                socket?.connect()
                runOnUiThread { gameView.setConnectionStatus("Connected") }
            } catch (error: SecurityException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth permission denied") }
            } catch (error: IOException) {
                runOnUiThread { gameView.setConnectionStatus("Connect failed: ${error.message ?: "I/O error"}") }
            }
        }
    }

    private fun sendCommand(command: String) {
        val current = socket
        if (current == null || !current.isConnected) {
            gameView.setConnectionStatus("Not connected — tap CONNECT")
            return
        }
        thread(name = "WearBomber-RFCOMM-send") {
            try {
                current.outputStream.write("$command\n".toByteArray())
                current.outputStream.flush()
            } catch (error: IOException) {
                runOnUiThread { gameView.setConnectionStatus("Send failed: ${error.message ?: "I/O error"}") }
            } catch (error: SecurityException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth permission denied") }
            }
        }
    }

    override fun onDestroy() {
        try {
            socket?.close()
        } catch (_: IOException) {
            // Closing during Activity teardown is best-effort.
        }
        super.onDestroy()
    }
}
