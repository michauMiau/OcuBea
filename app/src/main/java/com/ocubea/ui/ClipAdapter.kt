package com.ocubea.ui

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ocubea.R
import com.ocubea.model.OcuBeaConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One row in the clip list. */
data class ClipItem(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val recording: Boolean,
) {
    val url: String get() = "/clips/$name"
}

/**
 * Adapter for the clip grid.
 *
 * Selection is kept in the adapter rather than in the activity so a rotation
 * does not silently clear the user's picks — the activity is recreated but the
 * adapter's state is re-derivable from the same clip list.
 */
class ClipAdapter(
    private val onPlay: (ClipItem) -> Unit,
    private val onDelete: (ClipItem) -> Unit,
    private val onSelectChanged: (Int) -> Unit,
) : RecyclerView.Adapter<ClipAdapter.VH>() {

    private val items = mutableListOf<ClipItem>()
    private val selected = mutableSetOf<String>()
    var selectionMode = false
        private set

    fun submit(list: List<ClipItem>) {
        items.clear()
        items.addAll(list)
        // A clip that vanished from disk must not stay selected, or the next
        // delete would target a name that no longer exists.
        selected.retainAll(items.map { it.name }.toSet())
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectChanged(selected.size)
    }

    fun selectedNames(): Set<String> = selected.toSet()

    fun clearSelection() {
        selected.clear()
        selectionMode = false
        notifyDataSetChanged()
        onSelectChanged(0)
    }

    class VH(val itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.title)
        val subtitle: TextView = itemView.findViewById(R.id.subtitle)
        val thumb: ImageView = itemView.findViewById(R.id.thumb)
        val del: ImageButton = itemView.findViewById(R.id.del)
        /** The clip this holder last rendered, so a late decode cannot land on a recycled row. */
        var boundName: String? = null
    }

    override fun getItemCount() = items.size
    override fun getItemId(position: Int) = items[position].name.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clip, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        // Named `clip`, not `it`: inside setOnClickListener the implicit `it`
        // refers to the View, and the outer clip would be shadowed.
        val clip = items[position]
        h.boundName = clip.name
        h.title.text = prettyTime(clip.modifiedMs)
        h.subtitle.text = prettySize(clip.sizeBytes)
        h.thumb.contentDescription = clip.name
        bindThumb(h, clip)
        // A selection ring on the card itself: the card is the touch target, so
        // the state has to be visible where the user is already looking.
        h.itemView.isSelected = clip.name in selected
        h.thumb.alpha = if (clip.recording) 0.4f else 1f
        h.del.isEnabled = !clip.recording

        // Tapping the card plays; long press enters selection. Folding the two
        // into one gesture would make bulk delete impossible without a mode
        // button that most people never find.
        h.itemView.setOnClickListener {
            if (selectionMode) toggle(clip) else onPlay(clip)
        }
        h.itemView.setOnLongClickListener {
            selectionMode = true
            toggle(clip)
            true
        }
        h.del.setOnClickListener { onDelete(clip) }
    }

    /**
     * Shows the poster frame, or the play glyph while it decodes or if it
     * cannot be decoded at all.
     *
     * The decode happens on a background thread, so by the time it returns the
     * holder may already have been rebound to a different clip. The name check
     * is what stops a slow decode from painting the previous clip's frame onto
     * the row the user is now looking at.
     */
    private fun bindThumb(h: VH, clip: ClipItem) {
        val ctx = h.itemView.context
        val cached = ClipThumbs.cached(ctx, clip)
        if (cached != null) {
            h.thumb.setImageBitmap(cached)
            return
        }
        h.thumb.setImageResource(R.drawable.ic_play_circle)
        if (clip.recording) return
        ClipThumbs.load(ctx, clip) { bmp ->
            if (h.boundName != clip.name) return@load
            if (bmp == null) return@load
            h.thumb.setImageBitmap(bmp)
        }
    }

    private fun toggle(clip: ClipItem) {
        if (!selected.add(clip.name)) selected.remove(clip.name)
        if (selected.isEmpty()) selectionMode = false
        notifyDataSetChanged()
        onSelectChanged(selected.size)
    }

    /**
     * The base URL the running server answers on.
     *
     * Clips are read through the same HTTP endpoint the browser uses, so the
     * player gets the exact byte ranges it would get in a browser and there is
     * only one file-reading path to keep correct.
     */

    /** Formats a duration or falls back to the timestamp. */
    companion object {
        /**
         * Built per call, not cached in a val: a SimpleDateFormat captured in a
         * static field keeps the locale it was created with, so a user who
         * switches language mid-session would still see the old formatting.
         */
        private fun fmt() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        /**
         * The base URL the running server answers on.
         *
         * Clips are read through the same HTTP endpoint the browser uses, so
         * the player gets the exact byte ranges it would get in a browser and
         * there is only one file-reading path to keep correct.
         */
        fun baseUrl(ctx: Context): String {
            val cfg = OcuBeaConfig(ctx)
            // 127.0.0.1 keeps the request on the device; the service listens on
            // all interfaces, so a loopback client gets the same files.
            return "http://127.0.0.1:${cfg.port}"
        }

        fun uriFor(ctx: Context, item: ClipItem): Uri =
            Uri.parse("${baseUrl(ctx)}/clips/${Uri.encode(item.name)}")

        fun prettyTime(ms: Long): String = fmt().format(Date(ms))

        fun prettySize(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L -> "%d kB".format(bytes / 1024)
            else -> "$bytes B"
        }
    }
}
