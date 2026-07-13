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
        // 58mm is the printer's nominal/marketed paper size (what's actually loaded as roll
        // stock); the 384px native render width (see CLAUDE.md's "Technical constraints") only
        // covers the narrower ~48mm printable strip within that, which PrintService already
        // renders at correctly regardless of what width is declared here. Height is just a
        // bounded stand-in for "however long the content ends up being" -- Android's print
        // framework needs a finite page size, and longer documents simply paginate into multiple
        // PdfRenderer pages, each printed as its own bitmap (see SunmiPrintService). Keeping this
        // moderate (not e.g. a multi-hundred-mm "endless roll" ratio) matters for photos: apps'
        // default image print adapters scale to *fill* the whole declared page and crop whatever
        // overflows, so a page far taller than it is wide forces brutal cropping down to a sliver
        // of the image. This ratio is a compromise, not a perfect fit for every aspect ratio.
        val mediaSize = PrintAttributes.MediaSize("SUNMI_ROLL_58MM", "Receipt Roll (58mm)", 2283, 3543)
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
