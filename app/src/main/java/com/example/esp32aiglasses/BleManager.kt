package com.example.esp32aiglasses

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
private val COMMAND_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
private val STATUS_CHAR_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ae")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class BleManager(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private val statusChannel = Channel<String>(capacity = Channel.BUFFERED)
    private var pendingReadContinuation: ((Result<String>) -> Unit)? = null

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun scanAndConnect(targetName: String = "ESP32-Glasses") {
        if (!hasBluetoothPermission()) {
            throw IllegalStateException("Bluetooth permissions not granted")
        }

        val bluetoothAdapter = adapter ?: throw IllegalStateException("Bluetooth not available")
        val scanner = bluetoothAdapter.bluetoothLeScanner ?: throw IllegalStateException("BLE scanner not available")

        val device = suspendCancellableCoroutine<android.bluetooth.BluetoothDevice> { cont ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name ?: result.scanRecord?.deviceName
                    val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

                    if (name == targetName || uuids.contains(SERVICE_UUID)) {
                        scanner.stopScan(this)
                        cont.resume(result.device)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    cont.resumeWithException(IllegalStateException("Scan failed: $errorCode"))
                }
            }

            scanner.startScan(callback)
            cont.invokeOnCancellation { scanner.stopScan(callback) }
        }

        connect(device)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connect(device: android.bluetooth.BluetoothDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            var resumed = false

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (!resumed) {
                            resumed = true
                            cont.resumeWithException(IllegalStateException("GATT connect failed: $status"))
                        }
                        return
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        disconnect()
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (!resumed) {
                            resumed = true
                            cont.resumeWithException(IllegalStateException("Service discovery failed: $status"))
                        }
                        return
                    }

                    val service = g.getService(SERVICE_UUID)
                        ?: run {
                            if (!resumed) {
                                resumed = true
                                cont.resumeWithException(IllegalStateException("Service not found"))
                            }
                            return
                        }

                    commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                    statusChar = service.getCharacteristic(STATUS_CHAR_UUID)

                    if (commandChar == null || statusChar == null) {
                        if (!resumed) {
                            resumed = true
                            cont.resumeWithException(IllegalStateException("Characteristics missing"))
                        }
                        return
                    }

                    try {
                        gatt = g
                        enableNotifications(g, statusChar!!)
                    } catch (e: Exception) {
                        if (!resumed) {
                            resumed = true
                            cont.resumeWithException(e)
                        }
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    if (descriptor.characteristic.uuid == STATUS_CHAR_UUID) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            if (!resumed) {
                                resumed = true
                                cont.resume(Unit)
                            }
                        } else {
                            if (!resumed) {
                                resumed = true
                                cont.resumeWithException(
                                    IllegalStateException("Failed to enable notifications: $status")
                                )
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    val data = characteristic.value ?: return
                    if (characteristic.uuid == STATUS_CHAR_UUID) {
                        statusChannel.trySend(data.toString(Charsets.UTF_8))
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (characteristic.uuid == STATUS_CHAR_UUID) {
                        val callback = pendingReadContinuation
                        pendingReadContinuation = null

                        if (callback != null) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                val msg = characteristic.value?.toString(Charsets.UTF_8) ?: ""
                                callback(Result.success(msg))
                            } else {
                                callback(Result.failure(IllegalStateException("Read failed: $status")))
                            }
                        }
                    }
                }
            }

            gatt = device.connectGatt(context, false, callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val notifyOk = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifyOk) {
            throw IllegalStateException("Failed to enable local notifications")
        }

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
            ?: throw IllegalStateException("CCCD descriptor missing")

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = gatt.writeDescriptor(descriptor)
        if (!ok) {
            throw IllegalStateException("Failed to write CCCD descriptor")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun sendCommand(command: String) {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val ch = commandChar ?: throw IllegalStateException("Command characteristic missing")
        val bytes = command.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeCharacteristic(
                ch,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (result != BluetoothGatt.GATT_SUCCESS) {
                throw IllegalStateException("Write failed: $result")
            }
        } else {
            @Suppress("DEPRECATION")
            ch.value = bytes
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(ch)
            if (!ok) {
                throw IllegalStateException("Write failed")
            }
        }
    }

    suspend fun waitForStatusMessage(): String {
        return statusChannel.receive()
    }

    @SuppressLint("MissingPermission")
    suspend fun readStatusValue(): String {
        val g = gatt ?: throw IllegalStateException("Not connected")
        val ch = statusChar ?: throw IllegalStateException("Status characteristic missing")

        return suspendCancellableCoroutine { cont ->
            pendingReadContinuation = { result ->
                result.onSuccess { cont.resume(it) }
                result.onFailure { cont.resumeWithException(it) }
            }

            val ok = g.readCharacteristic(ch)
            if (!ok) {
                pendingReadContinuation = null
                cont.resumeWithException(IllegalStateException("Failed to start characteristic read"))
            }
        }
    }

    fun clearStatusMessages() {
        while (true) {
            val result = statusChannel.tryReceive()
            if (result.isFailure) break
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        commandChar = null
        statusChar = null
        pendingReadContinuation = null
        clearStatusMessages()
    }
}