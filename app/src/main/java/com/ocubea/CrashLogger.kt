package com.ocubea

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught crashes to the app's external files dir (logs/crash.log) so
 * failures can be diagnosed from a file manager when ADB is not available.
 *
 * Also keeps a ring of recent non-fatal errors via [log] for the in-app log view.
 */
object CrashLogger {

    @Volatile var installed = false
        private set

    private const val MAX_LOG_BYTES = 256 * 1024

    fun installIfNeeded(context: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                var cause = throwable.cause
                var depth = 0
                while (cause != null && cause !== throwable && depth < 8) {
                    sw.append("\n\nCAUSED BY: ")
                    cause.printStackTrace(PrintWriter(sw))
                    cause = cause.cause
                    depth++
                }
                append(
                    context,
                    "\n==== ${timestamp()} thread=${thread.name} ====\n$sw"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Log a recoverable error without crashing. */
    fun log(context: Context, tag: String, message: String) {
        runCatching { append(context, "[${timestamp()}] $tag: $message\n") }
    }

    private fun append(context: Context, text: String) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        dir.mkdirs()
        val file = File(dir, "crash.log")
        // Keep the log bounded so it can never fill the user's storage
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val kept = file.readText().takeLast(MAX_LOG_BYTES / 2)
            file.writeText(kept)
        }
        file.appendText(text)
    }

    fun lastLog(context: Context, chars: Int = 4000): String? {
        val f = File(File(context.getExternalFilesDir(null) ?: context.filesDir, "logs"), "crash.log")
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return if (text.length <= chars) text else text.takeLast(chars)
    }

    fun clear(context: Context) {
        runCatching {
            File(File(context.getExternalFilesDir(null) ?: context.filesDir, "logs"), "crash.log")
                .delete()
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
