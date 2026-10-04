package com.ocubea.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.ocubea.R
import com.ocubea.clip.ClipApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native clip management: list, play, record on demand, delete, prune.
 *
 * Playback and listing go through the app's own HTTP server rather than reading
 * files directly, so the Range handling that the browser relies on is the same
 * code being exercised here — if it works in the app it works in the WebUI.
 */
class ClipActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: ClipAdapter
    private lateinit var player: ExoPlayer
    private lateinit var playerHost: View
    private lateinit var emptyView: TextView
    private lateinit var recBadge: TextView
    private lateinit var statusLine: TextView
    private val api by lazy { ClipApi(ClipAdapter.baseUrl(this)) }
    private var polling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        // Measured on a Redmi Note 12 Pro, Android 16 (API 36): before this
        // call tvTitle sat at y=27-86 while the status bar occupied y=0-94,
        // so 67 px of the title was under the clock. See EdgeToEdgeInsets.
        EdgeToEdgeInsets.padForSystemBars(findViewById(android.R.id.content))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        playerHost = findViewById(R.id.playerHost)
        emptyView = findViewById(R.id.tvEmpty)
        recBadge = findViewById(R.id.tvRecBadge)
        statusLine = findViewById(R.id.tvClipStatus)

        player = ExoPlayer.Builder(this).build()
        (playerHost as FrameLayout).addView(
            PlayerView(this).apply {
                useController = true
                player = this@ClipActivity.player
            },
            FrameLayout.LayoutParams(-1, -1),
        )

        adapter = ClipAdapter(
            onPlay = ::play,
            onDelete = ::confirmDelete,
            onSelectChanged = ::renderSelection,
        )
        findViewById<RecyclerView>(R.id.clipList).apply {
            layoutManager = GridLayoutManager(this@ClipActivity, 2)
            adapter = this@ClipActivity.adapter
        }

        findViewById<SwipeRefreshLayout>(R.id.swipe).setOnRefreshListener { refresh() }
        findViewById<Button>(R.id.btnRecord).setOnClickListener { toggleRecord() }
        findViewById<Button>(R.id.btnPrune).setOnClickListener { confirmPrune() }
        findViewById<Button>(R.id.btnDeleteSelected).setOnClickListener { confirmDeleteSelected() }
        findViewById<View>(R.id.btnPlayerClose).setOnClickListener { stopPlayback() }

        refresh()
        startPolling()
    }

    override fun onDestroy() {
        scope.cancel()
        player.release()
        super.onDestroy()
    }

    // ── Data ───────────────────────────────────────────────────

    private fun refresh() {
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { api.list() } }
            val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe)
            swipe.isRefreshing = false
            result.onSuccess { snapshot ->
                adapter.submit(snapshot.clips.map {
                    ClipItem(it.name, it.size, it.modified, it.recording)
                })
                renderSnapshot(snapshot)
            }.onFailure {
                toast("Nie udało się pobrać listy: ${it.message}")
            }
        }
    }

    private fun renderSnapshot(s: ClipApi.Snapshot) {
        emptyView.visibility = if (s.clips.isEmpty()) View.VISIBLE else View.GONE
        recBadge.visibility = if (s.recording) View.VISIBLE else View.GONE
        statusLine.text = getString(
            R.string.clip_status,
            s.clips.size,
            ClipAdapter.prettySize(s.bytes),
            ClipAdapter.prettySize(s.free),
        )
    }

    /**
     * Polls while the screen is open.
     *
     * The list has to reflect a recording in progress and clips the retention
     * sweep removed underneath us; neither pushes a notification, so a timer is
     * the honest option. Four seconds matches the server-side update interval
     * and stays cheap because the response is a short JSON array.
     */
    private fun startPolling() {
        if (polling) return
        polling = true
        scope.launch {
            while (isActive) {
                delay(4000)
                if (!polling) break
                runCatching { withContext(Dispatchers.IO) { api.list() } }
                    .onSuccess { snapshot ->
                        adapter.submit(snapshot.clips.map {
                            ClipItem(it.name, it.size, it.modified, it.recording)
                        })
                        renderSnapshot(snapshot)
                    }
            }
        }
    }

    // ── Playback ───────────────────────────────────────────────

    private fun play(item: ClipItem) {
        playerHost.visibility = View.VISIBLE
        player.setMediaItem(MediaItem.fromUri(ClipAdapter.uriFor(this, item)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun stopPlayback() {
        player.stop()
        player.clearMediaItems()
        playerHost.visibility = View.GONE
    }

    override fun onPause() {
        super.onPause()
        // Background audio from a clip the user walked away from is never wanted.
        player.pause()
    }

    // ── Actions ────────────────────────────────────────────────

    private fun toggleRecord() {
        scope.launch {
            val busy = runCatching { withContext(Dispatchers.IO) { api.isRecording() } }
                .getOrNull() ?: return@launch
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    if (busy) api.stopRecording() else api.startRecording(10)
                }
            }.isSuccess
            if (ok) {
                toast(if (busy) "Zapis zatrzymany" else "Nagrywam 10 s")
                refresh()
            } else {
                toast("Nie udało się zmienić stanu nagrywania")
            }
        }
    }

    private fun confirmDelete(item: ClipItem) {
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage("Usunąć ten klip?")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ -> delete(setOf(item.name)) }
            .show()
    }

    private fun confirmDeleteSelected() {
        val names = adapter.selectedNames()
        if (names.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Usunąć ${names.size} klip(y)?")
            .setMessage("Tej operacji nie da się cofnąć.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ -> delete(names) }
            .show()
    }

    private fun delete(names: Set<String>) {
        scope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    if (names.size == 1) api.deleteOne(names.first()) else api.deleteMany(names)
                }
            }.isSuccess
            toast(if (ok) "Usunięto" else "Nie udało się usunąć")
            adapter.clearSelection()
            refresh()
        }
    }

    private fun confirmPrune() {
        AlertDialog.Builder(this)
            .setTitle("Usunąć stare klipy?")
            .setMessage("Zadziała limit wieku, rozmiaru i liczby plików. " +
                "Klip właśnie nagrywany zostaje.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ ->
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { api.prune() } }
                    r.onSuccess { toast("Usunięto ${it.removed}") }
                        .onFailure { toast("Nie udało się: ${it.message}") }
                    refresh()
                }
            }
            .show()
    }

    private fun renderSelection(count: Int) {
        val show = if (count > 0) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvSelected).apply {
            visibility = show
            text = getString(R.string.clip_selected, count)
        }
        findViewById<Button>(R.id.btnDeleteSelected).visibility = show
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
