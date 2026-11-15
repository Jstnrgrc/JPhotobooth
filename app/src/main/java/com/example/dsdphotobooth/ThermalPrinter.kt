package com.example.dsdphotobooth

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class PrinterProfile(val connection: String, val id: String, val name: String, val width: Int, val cut: ReceiptCut, val cutClearanceMm: Int)
internal data class PrinterChoice(val connection: String, val id: String, val name: String)
internal data class PrintResult(val status: String, val message: String)

internal class ThermalPrinter(private val context: Context) {
    companion object { private val outputLock = java.util.concurrent.locks.ReentrantLock() }
    private val prefs = context.getSharedPreferences("thermal_printer", Context.MODE_PRIVATE)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val bt = context.getSystemService(BluetoothManager::class.java)
    private val permissionAction = context.packageName + ".USB_PRINTER_PERMISSION"

    fun profile(): PrinterProfile? = prefs.getString("id", null)?.let {
        PrinterProfile(prefs.getString("connection", "")!!, it, prefs.getString("name", "Printer")!!,
            prefs.getInt("width",576), ReceiptCut.entries.firstOrNull { it.name == prefs.getString("cut_mode",null) }
                ?: if (!prefs.contains("cut_mode") && prefs.getBoolean("cut",false)) ReceiptCut.PARTIAL else ReceiptCut.FULL,
            prefs.getInt("cut_clearance_mm",EscPosRaster.DEFAULT_CUT_CLEARANCE_MM).coerceIn(EscPosRaster.cutClearanceRange))
    }
    fun save(choice: PrinterChoice, width: Int, cut: ReceiptCut, cutClearanceMm: Int) {
        require(width in 384..832 && width % 8 == 0 && cutClearanceMm in EscPosRaster.cutClearanceRange)
        prefs.edit().putString("connection",choice.connection).putString("id",choice.id)
            .putString("name",choice.name).putInt("width",width).putString("cut_mode",cut.name).putInt("cut_clearance_mm",cutClearanceMm).apply()
    }
    @SuppressLint("MissingPermission")
    fun bluetoothChoices(): List<PrinterChoice> =
        bt?.adapter?.bondedDevices.orEmpty().map {
            PrinterChoice("Bluetooth",it.address,it.name ?: it.address)
        }.sortedBy { it.name }

    private fun usbId(device: UsbDevice) = "${device.vendorId}:${device.productId}"
    private fun endpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_PRINTER) continue
            for (n in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(n)
                if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
                    return intf to ep
            }
        }
        return null
    }
    fun usbChoices(): List<PrinterChoice> = usb?.deviceList?.values.orEmpty()
        .filter { endpoint(it) != null }.groupBy { usbId(it) }.values
        .filter { it.size == 1 }.map { group ->
            val device = group.single()
            PrinterChoice("USB",usbId(device),device.productName ?: "USB printer (${usbId(device)})")
        }.sortedBy { it.name }
    private fun findUsb(id: String) =
        usb?.deviceList?.values.orEmpty().filter { usbId(it) == id && endpoint(it) != null }.singleOrNull()

    fun usbAccess(id: String) = findUsb(id)?.let { usb?.hasPermission(it) } == true

    fun requestUsbAccess(id: String): Boolean {
        val device = findUsb(id) ?: return false
        val intent = Intent(permissionAction).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 419, intent, PendingIntent.FLAG_IMMUTABLE)
        usb?.requestPermission(device,pending)
        return true
    }

    fun send(profile: PrinterProfile, image: List<ByteArray>, copies: Int = 1): PrintResult {
        val receipts = EscPosRaster.copies(image,profile.width,copies,profile.cutClearanceMm,profile.cut)
        if (!outputLock.tryLock()) return PrintResult("FAILED","The printer is busy. Please try again.")
        var writing = false
        val timeoutSeconds = 35L * copies
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000L
        fun transmit(send: (ByteArray) -> Unit) {
            receipts.forEachIndexed { index, packets ->
                if (index > 0) SystemClock.sleep(750L) // allow time for feed and cutter before sending the next image
                packets.forEach {
                    if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Printer timed out.")
                    writing = true
                    send(it)
                    SystemClock.sleep(15)
                }
            }
        }
        return try {
            when(profile.connection) {
                "Bluetooth" -> bluetooth(profile, timeoutSeconds) { write -> transmit(write) }
                "USB" -> usb(profile) { write -> transmit(write) }
                else -> throw IOException("Select a printer in Admin → Printer setup.")
            }
            PrintResult("TRANSMITTED","Receipt sent. Please check the printer.")
        } catch (error: Exception) {
            if(writing) PrintResult("UNKNOWN","The printer may have received part or all of the receipt. Ask the attendant before printing again.")
            else PrintResult("FAILED",error.message ?: "Could not connect to the printer.")
        } finally { outputLock.unlock() }
    }
    @SuppressLint("MissingPermission")
    private fun bluetooth(profile: PrinterProfile, timeoutSeconds: Long, transmit: ((ByteArray) -> Unit) -> Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw IOException("Allow Nearby Devices in Admin → Printer setup.")
        val adapter = bt?.adapter ?: throw IOException("Bluetooth is unavailable.")
        if (!adapter.isEnabled) throw IOException("Turn on Bluetooth and make sure the printer is paired.")
        val device = adapter.bondedDevices.firstOrNull { it.address == profile.id }
            ?: throw IOException("Pair this printer in Android Bluetooth settings.")
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule({ runCatching { socket.close() } }, timeoutSeconds, TimeUnit.SECONDS)
        try {
            socket.use {
                it.connect()
                val output = it.outputStream
                transmit { packet -> output.write(packet); output.flush() }
            }
        } finally {
            timeout.cancel(false)
            watchdog.shutdown()
        }
    }
    private fun usb(profile: PrinterProfile, transmit: ((ByteArray) -> Unit) -> Unit) {
        val manager = usb ?: throw IOException("USB host is unavailable.")
        val device = findUsb(profile.id) ?: throw IOException("Reconnect the selected USB printer. Connect only one of this model.")
        if (!manager.hasPermission(device)) throw IOException("Allow USB access in Admin → Printer setup.")
        val (intf, ep) = endpoint(device) ?: throw IOException("This printer does not expose a standard USB printer interface.")
        val connection = manager.openDevice(device) ?: throw IOException("Could not open the USB printer.")
        try {
            if (!connection.claimInterface(intf,true)) throw IOException("Could not claim the USB printer interface.")
            try {
                transmit { packet ->
                    var offset = 0
                    while(offset < packet.size) {
                        val n = connection.bulkTransfer(ep,packet,offset,packet.size-offset,2000)
                        if (n <= 0) throw IOException("USB printer stopped accepting data.")
                        offset += n
                    }
                }
            } finally { connection.releaseInterface(intf) }
        } finally { connection.close() }
    }
}
