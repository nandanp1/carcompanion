@file:Suppress("DEPRECATION")
package com.carcompanion.app.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.carcompanion.app.BuildConfig
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.model.DisplayState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

data class ConnectionInfo(
    val connected: Boolean = false,
    val status: String = "Disconnected",
    val mtu: Int = 23,
    val firmwareVersion: String = "Unknown",
)

/** Owns one GATT connection. Every ATT operation is serialized and times out. */
@SuppressLint("MissingPermission")
class BleClient(private val context: Context, private val onPlaybackCommand: (Int) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableConnection = MutableStateFlow(ConnectionInfo())
    val connection: StateFlow<ConnectionInfo> = mutableConnection
    private val lifecycle = ConnectionLifecycle(scope)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var wantedAddress: String? = null
    private var connectedSignal = CompletableDeferred<Unit>()
    private var disconnectedSignal = CompletableDeferred<Unit>()
    private val operationMutex = Mutex()
    private val jsonMutex = Mutex()
    private val stateMutex = Mutex()
    private val artworkMutex = Mutex()
    private data class Operation(val owner: BluetoothGatt, val type: String, val uuid: UUID?, val result: CompletableDeferred<Int>)
    @Volatile private var operation: Operation? = null
    private val waiters = mutableMapOf<Pair<Int, Int>, Channel<Protocol.Status>>()
    @Volatile private var mtu = 23
    @Volatile private var cachedCrc = 0L
    private var nextId = 1
    private var lastPlaybackSequence = -1
    private var lastState: DisplayState? = null
    private var lastClockMinute = Long.MIN_VALUE
    private var brightness = 70

    fun connect(rawAddress: String) {
        val address = BluetoothAddress.canonicalize(rawAddress)
        // Also migrate addresses saved by older versions, without requiring re-association.
        val preferences = context.getSharedPreferences("car_companion", Context.MODE_PRIVATE)
        if (preferences.getString("device_address", null) != address) {
            preferences.edit().putString("device_address", address).apply()
        }
        if (wantedAddress == address && lifecycle.isActive) return
        wantedAddress = address
        lifecycle.replace {
            var attempt = 0
            while (isActive && wantedAddress == address) {
                try {
                    mutableConnection.value = ConnectionInfo(status = "Connecting…")
                    mtu = 23
                    cachedCrc = 0
                    lastPlaybackSequence = -1
                    lastState = null
                    lastClockMinute = Long.MIN_VALUE
                    connectedSignal = CompletableDeferred()
                    disconnectedSignal = CompletableDeferred()
                    val manager = context.getSystemService(BluetoothManager::class.java)
                    val adapter = manager?.adapter ?: error("Bluetooth is unavailable")
                    check(adapter.isEnabled) { "Turn on Bluetooth" }
                    val device = adapter.getRemoteDevice(address)
                    logConnection("BLE connecting · attempt ${attempt + 1}")
                    val active = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                        ?: error("Unable to create GATT client")
                    gatt = active
                    timed(20_000, "Connection") { connectedSignal.await() }
                    ensureBond(device)
                    logConnection("BLE bond ready · discovering services")
                    perform(active, "services") { active.discoverServices() }
                    check(active.getService(Protocol.SERVICE) != null) { "Car Companion service missing" }
                    // Some devices decline the MTU request: continuing at the current MTU is safe.
                    runCatching { perform(active, "mtu") { active.requestMtu(247) } }.onFailure {
                        if (it is CancellationException) throw it
                        logConnection("BLE MTU remains $mtu: ${it.message}")
                    }
                    subscribe(Protocol.CONTROL)
                    subscribe(Protocol.PLAYBACK)
                    logConnection("BLE notifications enabled · MTU $mtu")
                    val hello = exchange(1, 0, JSONObject().put("op", "hello"))
                    check(hello.maxKiB >= 32) { "Firmware artwork capacity unsupported" }
                    cachedCrc = hello.cachedCrc
                    mutableConnection.value = ConnectionInfo(true, "Connected", mtu,
                        if (hello.offset == 1L) "0.1.0 / protocol 1" else "Build ${hello.offset} / protocol 1")
                    sendJson(Protocol.CONTROL, JSONObject().put("op", "brightness").put("value", brightness))
                    attempt = 0
                    logConnection("BLE ready · MTU $mtu · ${mutableConnection.value.firmwareVersion}")
                    disconnectedSignal.await()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    logConnection("BLE: ${failure.message}")
                    mutableConnection.value = mutableConnection.value.copy(connected = false, status = failure.message ?: "Connection failed")
                } finally {
                    val previous = gatt
                    gatt = null
                    operation?.result?.completeExceptionally(IllegalStateException("GATT disconnected"))
                    operation = null
                    synchronized(waiters) { waiters.values.forEach { it.close() }; waiters.clear() }
                    runCatching { previous?.disconnect() }
                    runCatching { previous?.close() }
                }
                if (wantedAddress == address && isActive) {
                    val delayMs = Protocol.retryDelay(attempt++) + Random.nextLong(0, 300)
                    mutableConnection.value = mutableConnection.value.copy(connected = false, status = "Reconnecting in ${delayMs / 1000}s")
                    logConnection("BLE reconnect scheduled · ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
    }

    fun disconnect() {
        if (wantedAddress != null) logConnection("BLE stopped by app")
        wantedAddress = null
        lifecycle.cancel()
        runCatching { gatt?.disconnect() }
        mutableConnection.value = mutableConnection.value.copy(connected = false, status = "Disconnected")
    }
    fun close() { disconnect(); scope.cancel() }

    private fun logConnection(message: String) {
        val bounded = message.take(240)
        AppStore.log(bounded)
        if (BuildConfig.DEBUG) Log.d("CarCompanionBLE", bounded)
    }

    fun setBrightness(value: Int) {
        brightness = value.coerceIn(5, 100)
        if (connection.value.connected) scope.launch {
            runCatching { sendJson(Protocol.CONTROL, JSONObject().put("op", "brightness").put("value", brightness)) }
                .onFailure { AppStore.log("Brightness: ${it.message}") }
        }
    }

    suspend fun sendState(state: DisplayState) = stateMutex.withLock {
        if (!connection.value.connected) return@withLock
        if (state.demo != lastState?.demo) sendJson(Protocol.CONTROL, JSONObject().put("op", "demo").put("enabled", state.demo))
        sendJson(Protocol.DRIVING, StateEncoder.driving(state))
        if (state.navigation != lastState?.navigation) sendJson(Protocol.NAVIGATION, StateEncoder.navigation(state))
        if (state.music != lastState?.music) sendJson(Protocol.MUSIC, StateEncoder.music(state))
        val seconds = System.currentTimeMillis() / 1000
        if (seconds / 60 != lastClockMinute) {
            sendJson(Protocol.CONTROL, JSONObject().put("op", "clock").put("unix", seconds)
                .put("offset_min", TimeZone.getDefault().getOffset(seconds * 1000) / 60_000))
            lastClockMinute = seconds / 60
        }
        lastState = state
    }

    suspend fun sendArtwork(bytes: ByteArray, contentId: Long) = artworkMutex.withLock {
        if (!connection.value.connected) return@withLock
        val crc = Protocol.crc32(bytes)
        require(crc == contentId) { "Artwork ID must equal JPEG CRC32" }
        if (crc != 0L && cachedCrc == crc) return@withLock
        val id = allocateId()
        val transfer = ArtworkTransfer(
            mtu = { mtu },
            begin = { transferId, size, checksum -> exchange(2, transferId,
                JSONObject().put("op", "art_begin").put("id", transferId).put("size", size).put("crc", checksum)) },
            write = { write(Protocol.ARTWORK, it) },
            commit = { transferId -> exchange(2, transferId, JSONObject().put("op", "art_commit").put("id", transferId)) },
        )
        transfer.send(bytes, id)
        cachedCrc = crc
        AppStore.log("Artwork verified · ${bytes.size} bytes")
    }

    private suspend fun ensureBond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        mutableConnection.value = mutableConnection.value.copy(status = "Confirm Bluetooth pairing")
        val signal = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                    BluetoothDevice.BOND_BONDED -> signal.complete(Unit)
                    BluetoothDevice.BOND_NONE -> signal.completeExceptionally(IllegalStateException("Pairing declined; retry from setup"))
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            if (device.bondState != BluetoothDevice.BOND_BONDING) check(device.createBond()) { "Pairing could not start" }
            timed(45_000, "Pairing") { signal.await() }
        } finally { context.unregisterReceiver(receiver) }
    }

    private suspend fun subscribe(uuid: UUID) {
        val active = gatt ?: error("Disconnected")
        val characteristic = characteristic(uuid)
        check(active.setCharacteristicNotification(characteristic, true))
        val descriptor = characteristic.getDescriptor(Protocol.CCCD) ?: error("Notification descriptor missing")
        perform(active, "descriptor", uuid) {
            if (Build.VERSION.SDK_INT >= 33) active.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; active.writeDescriptor(descriptor) }
        }
    }

    private suspend fun exchange(event: Int, id: Int, json: JSONObject): Protocol.Status {
        val key = event to id
        val channel = Channel<Protocol.Status>(8)
        synchronized(waiters) { check(waiters.put(key, channel) == null) { "Duplicate transaction waiter" } }
        try {
            sendJson(Protocol.CONTROL, json)
            return timed(12_000, "Firmware response") { channel.receive() }
        } finally {
            synchronized(waiters) { if (waiters[key] === channel) waiters.remove(key) }
            channel.close()
        }
    }

    private suspend fun sendJson(uuid: UUID, json: JSONObject) = jsonMutex.withLock {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..1024) { "State exceeds protocol bound" }
        val id = allocateId()
        var offset = 0
        while (offset < bytes.size) {
            val frame = Protocol.frame(bytes, id, offset, mtu)
            write(uuid, frame)
            offset += frame.size - Protocol.HEADER_SIZE
        }
    }
    @Synchronized private fun allocateId(): Int = nextId.also { nextId = if (it == 65535) 1 else it + 1 }
    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
        gatt?.getService(Protocol.SERVICE)?.getCharacteristic(uuid) ?: error("Characteristic $uuid unavailable")

    private suspend fun write(uuid: UUID, bytes: ByteArray) {
        val active = gatt ?: error("Disconnected")
        val characteristic = characteristic(uuid)
        perform(active, "write", uuid) {
            if (Build.VERSION.SDK_INT >= 33) active.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; characteristic.value = bytes; active.writeCharacteristic(characteristic) }
        }
    }

    private suspend fun perform(active: BluetoothGatt, type: String, uuid: UUID? = null, start: () -> Boolean): Int = operationMutex.withLock {
        check(gatt === active) { "Queued GATT operation belongs to an expired connection" }
        val result = CompletableDeferred<Int>()
        operation = Operation(active, type, uuid, result)
        try {
            check(start()) { "GATT $type rejected" }
            val status = withTimeout(10_000) { result.await() }
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT $type status $status" }
            status
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            logConnection("BLE GATT $type timed out · closing connection")
            // A late callback can never be safely attributed to a subsequent operation.
            if (gatt === active) gatt = null
            disconnectedSignal.complete(Unit)
            mutableConnection.value = mutableConnection.value.copy(connected = false, status = "GATT timeout")
            runCatching { active.disconnect() }
            runCatching { active.close() }
            throw IllegalStateException("GATT $type timed out; reconnecting", timeout)
        } finally { operation = null }
    }

    private suspend fun <T> timed(milliseconds: Long, label: String, action: suspend () -> T): T =
        try { withTimeout(milliseconds) { action() } }
        catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("$label timed out", timeout)
        }

    private fun finish(active: BluetoothGatt, type: String, uuid: UUID?, status: Int) {
        if (active !== gatt) return
        operation?.takeIf { it.owner === active && it.type == type && it.uuid == uuid }?.result?.complete(status)
    }

    private fun notification(active: BluetoothGatt, uuid: UUID, bytes: ByteArray) {
        if (active !== gatt) return
        if (uuid == Protocol.CONTROL) {
            val status = runCatching { Protocol.status(bytes) }.getOrElse {
                AppStore.log("Unsupported or malformed firmware status"); runCatching { active.disconnect() }; return
            }
            cachedCrc = status.cachedCrc
            synchronized(waiters) { waiters[status.event to status.id]?.trySend(status) }
            if (status.event == 3) AppStore.log("Firmware protocol error ${status.code}")
        } else if (uuid == Protocol.PLAYBACK && bytes.size == 4 && bytes[0].toInt() == 1) {
            val command = bytes[1].toInt() and 255
            val sequence = (bytes[2].toInt() and 255) or ((bytes[3].toInt() and 255) shl 8)
            if (sequence != lastPlaybackSequence && command in 1..3) {
                lastPlaybackSequence = sequence
                onPlaybackCommand(command)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(active: BluetoothGatt, status: Int, newState: Int) {
            if (active !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                logConnection("BLE GATT connected · status $status")
                connectedSignal.complete(Unit)
            }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                logConnection("BLE disconnected · GATT $status · state $newState · pending ${operation?.type ?: "none"}")
                val failure = IllegalStateException("Disconnected (GATT $status)")
                connectedSignal.completeExceptionally(failure)
                disconnectedSignal.complete(Unit)
                operation?.result?.completeExceptionally(failure)
                mutableConnection.value = mutableConnection.value.copy(connected = false, status = "Disconnected (GATT $status)")
                synchronized(waiters) { waiters.values.forEach { it.close(failure) }; waiters.clear() }
            }
        }
        override fun onServicesDiscovered(active: BluetoothGatt, status: Int) = finish(active, "services", null, status)
        override fun onMtuChanged(active: BluetoothGatt, value: Int, status: Int) {
            if (active === gatt && status == BluetoothGatt.GATT_SUCCESS) mtu = value.coerceAtLeast(23)
            finish(active, "mtu", null, status)
        }
        override fun onDescriptorWrite(active: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            finish(active, "descriptor", descriptor.characteristic.uuid, status)
        override fun onCharacteristicWrite(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            finish(active, "write", characteristic.uuid, status)
        override fun onCharacteristicChanged(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = notification(active, characteristic.uuid, value)
        override fun onCharacteristicChanged(active: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) notification(active, characteristic.uuid, characteristic.value ?: return)
        }
    }
}
