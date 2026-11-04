package com.dacia.hybridcluster.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.io.IOException
import java.util.UUID

/** Połączenie z vLinkerem przez Bluetooth SPP (RFCOMM). Logika transportu jest w [StreamElmLink]. */
object BluetoothElmLink {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    @Throws(IOException::class)
    fun connect(device: BluetoothDevice): ElmLink {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
        } catch (e: IOException) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
        return StreamElmLink(s.inputStream, s.outputStream) { s.close() }
    }
}
