package com.ocubea.perf

import com.ocubea.ui.EdgeToEdgeInsets
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ocubea.R
import java.util.Locale

/**
 * A read-only report of what [Metrics] has recorded, refreshed on a timer.
 *
 * This is deliberately not a sampling profiler and does not pretend to be one.
 * It answers the question that a regression actually shows up in: is a span
 * that used to cost 1 ms now costing 4 ms, on this build, on this phone. The
 * numbers are already in the process; this screen only renders them.
 *
 * It also carries the two limits in plain sight rather than in a tooltip:
 *
 * - Percentiles describe only the ring's newest window, so a long run shows
 *   `truncated` and says so instead of presenting a recent sample as the
 *   whole session.
 * - `max` is cumulative, so it survives a spike that has already rolled out
 *   of the window. Comparing p95 against the *previous run's* p95 is the
 *   valid comparison; comparing it against this screen's own `max` is not.
 *
 * Nothing here writes to the camera or the server. Enabling recording is the
 * only state this screen changes, and switching it off drops the samples so a
 * later view cannot show stale numbers.
 */
class PerfActivity : AppCompatActivity() {

    private lateinit var tvReport: TextView
    private lateinit var tvThread: TextView
    private lateinit var btnToggle: Button
    private val handler = Handler(Looper.getMainLooper())
    private val mainThreadTime = ThreadTime()

    private val tick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            render()
            // 1 s. The rings hold far more than that at frame rate, so a
            // faster tick would re-sort the same samples and cost CPU to
            // redraw an unchanged number.
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_perf)
        // Measured on a Redmi Note 12 Pro, Android 16 (API 36): before this
        // call tvTitle sat at y=27-86 while the status bar occupied y=0-94,
        // so 67 px of the title was under the clock. See EdgeToEdgeInsets.
        EdgeToEdgeInsets.padForSystemBars(findViewById(android.R.id.content))
        tvReport = findViewById(R.id.tvPerfReport)
        tvThread = findViewById(R.id.tvPerfThread)
        btnToggle = findViewById(R.id.btnPerfToggle)
        findViewById<Button>(R.id.btnPerfReset).setOnClickListener {
            Metrics.reset()
            render()
        }
        btnToggle.setOnClickListener {
            val on = !Metrics.enabled
            Metrics.setEnabled(on)
            btnToggle.text = getString(
                if (on) R.string.perf_stop else R.string.perf_start
            )
            render()
        }
        btnToggle.text = getString(
            if (Metrics.enabled) R.string.perf_stop else R.string.perf_start
        )
        // Take the first thread sample immediately: the one before it is the
        // "no previous point" case, so waiting a full interval would show
        // "unavailable" for a second after the screen opens for no reason.
        mainThreadTime.sample()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        // Remove the callback, not just the post: an Activity that is stopped
        // and resumed otherwise ends up with two ticks and doubles its own
        // refresh rate.
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val sb = StringBuilder()
        if (!Metrics.enabled) {
            sb.append(getString(R.string.perf_idle))
        } else {
            val snaps = Metrics.snapshot()
            if (snaps.isEmpty()) {
                sb.append(getString(R.string.perf_no_spans))
            } else {
                // Widest span name first, so the columns line up and the
                // expensive thing is at the top of the list.
                val width = snaps.keys.maxOf { it.length }.coerceAtLeast(5)
                sb.append(header(width))
                snaps.entries.sortedByDescending { it.value.p95Nanos }.forEach { (name, s) ->
                    sb.append(
                        row(
                            name, width, s.totalSamples,
                            s.p50Millis, s.p95Millis, s.p99Millis, s.maxMillis,
                            s.truncated,
                        )
                    )
                }
                sb.append(getString(R.string.perf_legend))
                val rejected = Metrics.rejectedSpans()
                if (rejected > 0) {
                    sb.append('\n').append(
                        getString(R.string.perf_rejected, rejected, Metrics.MAX_SPANS)
                    )
                }
            }
        }
        tvReport.text = sb.toString()

        // This thread's own share. Sampled here, on the main thread, so it
        // describes the UI thread and nothing else — the encode threads are
        // measured with `adb shell top -H`, which can see threads this API
        // cannot ask about on Android 6.
        val share = mainThreadTime.sample()
        tvThread.text = if (!mainThreadTime.isAvailable()) {
            getString(R.string.perf_thread_unavailable)
        } else if (share == null) {
            getString(R.string.perf_thread_sampling)
        } else {
            getString(R.string.perf_thread_value, share.percent)
        }
    }

    /**
     * Formats with an explicit [Locale.ROOT] and a fixed three-decimal
     * double, NOT `"%.3f"` through the default formatter.
     *
     * This is the same trap that broke HLS: `String.format` follows the device
     * locale, so on a Polish phone it emits `1,234` and the columns shift by
     * one character per row. Here that would only misalign a report, which is
     * why it is worth a comment rather than an apology — but the fix is the
     * same, and the failure is not obviously mine.
     *
     * The value is a Double, so a `%d` here would throw rather than round.
     */
    private fun row(
        name: String,
        width: Int,
        count: Long,
        p50: Double,
        p95: Double,
        p99: Double,
        max: Double,
        truncated: Boolean,
    ): String = String.format(
        Locale.ROOT,
        "%-${width}s %8d %8s %8s %8s %9s%s%n",
        name,
        count,
        millis(p50),
        millis(p95),
        millis(p99),
        millis(max),
        if (truncated) " *" else "",
    )

    private fun header(width: Int): String = String.format(
        Locale.ROOT,
        "%-${width}s %8s %8s %8s %8s %9s%n",
        "span", "n", "p50", "p95", "p99", "max",
    )

    /** Three decimals as a string, formatted without the default locale. */
    private fun millis(v: Double): String =
        String.format(Locale.ROOT, "%.3f", v)
}
