package com.example.sunmiprinttest

import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrinterDiscoverySession

// Reports exactly one virtual printer -- the Sunmi thermal printer itself isn't discoverable
// over the network, so there's nothing to actually search for; this just always advertises the
// single fixed printer as soon as discovery starts.
class SunmiPrinterDiscoverySession(private val service: SunmiPrintService) : PrinterDiscoverySession() {

    override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
        val printerId = service.printerId("sunmi_v2_pro")
        // 384px native width at ~203dpi (see CLAUDE.md's "Technical constraints") is ~48mm, the
        // standard printable width for 58mm thermal roll paper. Height is just a bounded stand-in
        // for "however long the receipt/photo ends up being" -- Android's print framework needs a
        // finite page size -- PrintService.processImageBitmap() prints the actual rendered height
        // regardless of this cap.
        val mediaSize = PrintAttributes.MediaSize("SUNMI_ROLL_48MM", "Receipt Roll (48mm)", 1890, 11800)
        val resolution = PrintAttributes.Resolution("sunmi_203dpi", "203 dpi", 203, 203)
        val capabilities = PrinterCapabilitiesInfo.Builder(printerId)
            .addMediaSize(mediaSize, true)
            .addResolution(resolution, true)
            .setColorModes(PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            .setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
            .build()
        val printerInfo = PrinterInfo.Builder(printerId, "Sunmi Printer", PrinterInfo.STATUS_IDLE)
            .setCapabilities(capabilities)
            .build()
        addPrinters(listOf(printerInfo))
    }

    override fun onStopPrinterDiscovery() {}
    override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
    override fun onStartPrinterStateTracking(printerId: PrinterId) {}
    override fun onStopPrinterStateTracking(printerId: PrinterId) {}
    override fun onDestroy() {}
}
