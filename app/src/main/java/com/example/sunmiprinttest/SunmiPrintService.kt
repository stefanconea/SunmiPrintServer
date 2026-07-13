package com.example.sunmiprinttest

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrinterId
import android.printservice.PrinterDiscoverySession
import android.printservice.PrintJob as SystemPrintJob
import android.printservice.PrintService as AndroidPrintService
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

// Bridges Android's system Print Framework (Settings -> Printing -> "Sunmi Printer", reachable
// from any app's Print action -- Gallery, Chrome, PDF viewers, ...) into this app's own bitmap
// pipeline. The system spools whatever the calling app rendered as a PDF; onPrintJobQueued()
// rasterizes each page with PdfRenderer, scales it to the fixed 384px print width, and hands it
// to PrintService.processImageBitmap() -- the same bitmap-first path every other input format
// (HTTP, ESC/POS, MQTT, desktop TCP) goes through. Registered in AndroidManifest.xml /
// res/xml/printservice.xml; the user still has to manually enable it once under Android's own
// Printing settings -- a system requirement for every print service (same as accessibility
// services), apps cannot self-enable it. Settings -> "Enable as Android Print Service" jumps
// straight to that system screen.
class SunmiPrintService : AndroidPrintService() {

    private var appService: PrintService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            appService = (binder as PrintService.LocalBinder).getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            appService = null
            isBound = false
        }
    }

    // Called when the system print spooler establishes/loses its connection to this service --
    // the right place to make sure the app's own foreground PrintService is up and bind to it,
    // since a print can be triggered without the Sunmi app ever having been opened.
    override fun onConnected() {
        super.onConnected()
        ContextCompat.startForegroundService(this, Intent(this, PrintService::class.java))
        bindService(Intent(this, PrintService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDisconnected() {
        if (isBound) { unbindService(connection); isBound = false }
        super.onDisconnected()
    }

    // generatePrinterId() is inherited from AndroidPrintService but SunmiPrinterDiscoverySession
    // lives outside that class hierarchy, so it needs a public path to call it.
    fun printerId(localId: String): PrinterId = generatePrinterId(localId)

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = SunmiPrinterDiscoverySession(this)

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onRequestCancelPrintJob(printJob: SystemPrintJob) {
        printJob.cancel()
    }

    // onPrintJobQueued() itself runs on the main thread (framework contract), and PrintJob's own
    // start()/complete()/fail()/cancel() all *require* the main thread -- calling them from the
    // background render thread throws IllegalAccessError. So start() happens here, PDF
    // rasterization happens on a background thread, and the background thread hops back to
    // mainHandler only to report complete()/fail().
    override fun onPrintJobQueued(printJob: SystemPrintJob) {
        printJob.start()
        val service = appService
        if (service == null) {
            printJob.fail("Sunmi Print Service isn't ready yet -- open the Sunmi app once and try again")
            return
        }
        val sourcePfd = printJob.document.data
        if (sourcePfd == null) {
            printJob.fail("No document data")
            return
        }
        thread {
            // The spooler hands the document over as a streamed pipe, but PdfRenderer requires
            // a seekable fd -- drain the pipe into a local cache file first, then open *that*.
            val tempFile = File(cacheDir, "print_job_${System.currentTimeMillis()}.pdf")
            try {
                ParcelFileDescriptor.AutoCloseInputStream(sourcePfd).use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                val renderer = PdfRenderer(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY))
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // A landscape page (e.g. a horizontal photo) has to be rotated 90 degrees --
                    // the printer only ever has 384px to work with in one fixed axis (the roll's
                    // width), so a wider-than-tall page must become taller-than-wide to print
                    // right-side-up on a narrow continuous roll, not sideways.
                    val isLandscape = page.width > page.height
                    val fitDimension = if (isLandscape) page.height else page.width
                    val scale = 384f / fitDimension
                    val renderWidth = (page.width * scale).toInt().coerceAtLeast(1)
                    val renderHeight = (page.height * scale).toInt().coerceAtLeast(1)
                    val rawBitmap = createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(rawBitmap)
                    canvas.drawColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(scale, scale) }
                    page.render(rawBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    val bitmap = if (isLandscape) {
                        // Rotating a landscape page onto a portrait strip has two valid
                        // directions (CW vs CCW) and nothing in the rasterized PDF page tells us
                        // which one is "upright" -- -90 matches what's actually been observed
                        // on real prints; if a future source ever needs the other direction,
                        // this is the one spot to reconsider.
                        val rotateMatrix = Matrix().apply { postRotate(-90f) }
                        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, rotateMatrix, true)
                    } else rawBitmap
                    service.processImageBitmap(bitmap, "Print Service")
                }
                renderer.close()
                mainHandler.post { printJob.complete() }
            } catch (e: Exception) {
                mainHandler.post { printJob.fail(e.message ?: "Print failed") }
            } finally {
                tempFile.delete()
            }
        }
    }
}
