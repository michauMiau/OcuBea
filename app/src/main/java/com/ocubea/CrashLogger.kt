package com.ocubea

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes uncaught crashes to /sdcard/Download/OcuBea-crash.log so failures
 * can be diagnosed without ADB attached.
 */
object CrashLogger {

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
                dir.mkdirs()
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                // include cause chain
                var cause = throwable.cause
                while (cause != null && cause !== throwable) {
                    sw.append("\n\nCAUSED BY: ")
                    cause.printStackTrace(PrintWriter(sw))
                    cause = cause.cause
                }
                File(dir, "crash.log").appendText(
                    "\n==== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())} thread=${thread.name} ====\n$sw"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastLog(context: Context): String? {
        val f = File(File(context.getExternalFilesDir(null) ?: context.filesDir, "logs"), "crash.log")
        return if (f.exists()) {
            val text = f.readText()
            text.substring(maxOf(0, text.length - 4000))
        } else null
    }
}
