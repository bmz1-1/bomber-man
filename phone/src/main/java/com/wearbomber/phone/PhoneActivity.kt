package com.wearbomber.phone

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

private val GAME_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")

class PhoneActivity : Activity() {
    private lateinit var gameView: PhoneGameView
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private val bluetoothRequest = 73

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        gameView = PhoneGameView(this) { command -> sendCommand(command) }
        gameView.onHostRequested = { startBluetoothHost() }
        setContentView(gameView)
        requestBluetoothPermissionsIfNeeded()
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (required.isNotEmpty()) requestPermissions(required.toTypedArray(), bluetoothRequest)
        }
    }

    private fun startBluetoothHost() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            gameView.setConnectionStatus("Bluetooth unavailable")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermissionsIfNeeded()
            gameView.setConnectionStatus("Grant Bluetooth permission, then tap HOST")
            return
        }
        thread(name = "WearBomber-RFCOMM-host") {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("WearBomber", GAME_UUID)
                runOnUiThread { gameView.setConnectionStatus("Waiting for watch…") }
                clientSocket = serverSocket?.accept()
                runOnUiThread { gameView.setConnectionStatus("Watch connected") }
                clientSocket?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        runOnUiThread { gameView.handleRemoteCommand(line) }
                    }
                }
                runOnUiThread { gameView.setConnectionStatus("Watch disconnected") }
            } catch (error: SecurityException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth permission denied") }
            } catch (error: IOException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth host error: ${error.message ?: "connection failed"}") }
            }
        }
    }

    private fun sendCommand(command: String) {
        val socket = clientSocket ?: return
        thread(name = "WearBomber-RFCOMM-send") {
            try {
                socket.outputStream.write("$command\n".toByteArray())
                socket.outputStream.flush()
            } catch (error: IOException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth send failed: ${error.message ?: "I/O error"}") }
            } catch (error: SecurityException) {
                runOnUiThread { gameView.setConnectionStatus("Bluetooth permission denied") }
            }
        }
    }

    override fun onDestroy() {
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (_: IOException) {
            // Closing during Activity teardown is best-effort.
        }
        super.onDestroy()
    }
}
