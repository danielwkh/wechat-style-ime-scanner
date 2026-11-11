package com.danielwkh.weime

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import com.dothantech.lpapi.LPAPI
import com.dothantech.printer.IDzPrinter
import com.dothantech.printer.IDzPrinter.PrintProgress
import com.dothantech.printer.IDzPrinter.PrinterAddress
import com.dothantech.printer.IDzPrinter.PrinterState

/** Thin wrapper around the official Detonger LPAPI SDK. */
class DtPrinterManager(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPrinterDiscovered(address: PrinterAddress)
        fun onPrinterState(address: PrinterAddress?, state: PrinterState)
        fun onPrintProgress(progress: PrintProgress)
    }

    private val api: LPAPI = LPAPI.Factory.createInstance(object : LPAPI.Callback {
        override fun onStateChange(address: PrinterAddress, state: PrinterState) {
            listener.onPrinterState(address, state)
        }

        override fun onProgressInfo(info: IDzPrinter.ProgressInfo, extra: Any?) = Unit

        override fun onPrinterDiscovery(address: PrinterAddress, extra: Any?) {
            listener.onPrinterDiscovered(address)
        }

        override fun onPrintProgress(
            address: PrinterAddress,
            bitmapData: IDzPrinter.PrintData,
            progress: PrintProgress,
            extra: Any?,
        ) {
            listener.onPrintProgress(progress)
        }
    })

    fun discover() {
        api.discovery()
    }

    fun stopDiscovery() {
        api.stopDiscovery()
    }

    fun connect(address: PrinterAddress): Boolean = api.openPrinterByAddress(address)

    fun isConnected(): Boolean {
        val state = api.printerState
        return state != null && state != PrinterState.Disconnected && state != PrinterState.Connecting
    }

    fun printerName(): String = api.printerName.orEmpty()

    fun print(bitmap: Bitmap, copies: Int, direction: Int, gapLength: Int, density: Int, speed: Int): Boolean {
        if (!isConnected()) return false
        val params = Bundle().apply {
            if (copies > 1) putInt(IDzPrinter.PrintParamName.PRINT_COPIES, copies)
            if (direction != 0) putInt(IDzPrinter.PrintParamName.PRINT_DIRECTION, direction)
            if (gapLength >= 0) putInt(IDzPrinter.PrintParamName.GAP_LENGTH, gapLength)
            if (density >= 0) putInt(IDzPrinter.PrintParamName.PRINT_DENSITY, density)
            if (speed >= 0) putInt(IDzPrinter.PrintParamName.PRINT_SPEED, speed)
        }
        return api.printBitmap(bitmap, params)
    }

    fun close() {
        api.stopDiscovery()
        api.quit()
    }
}
