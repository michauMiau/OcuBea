package com.ocubea.security

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one place that decides where clips live.
 *
 * getExternalMediaDirs() rather than getExternalFilesDir() because everything
 * under Android/data is hidden from the gallery and from most file managers
 * since Android 11 — clips written there are unfindable for the user, which is
 * the whole point of writing them. Android/media is indexed by MediaStore, so
 * the folder appears in the gallery and opens with a tap.
 *
 * There is no getExternalMediaDir(String): the platform only exposes
 * getExternalMediaDirs(), which returns one directory per mounted volume and
 * takes no subdirectory argument. The subfolder is therefore created inside
 * the returned root. Choosing the first entry is right here because a phone
 * with both internal and SD storage mounts both, and the primary
 * (non-removable) volume is listed first.
 *
 * getExternalMediaDirs() needs no permission, unlike Pictures/ or Movies/ which
 * on API 33 require a manual WRITE_EXTERNAL_STORAGE grant that is lost on
 * reinstall. The filesDir fallback is what happens with no external volume: the
 * clips then vanish when the app is uninstalled, so callers must report that
 * state rather than let the user hunt for files that are gone.
 */
object ClipStorage {

    private const val SUBDIR = "klipy"
    private const val PREFIX = "klip"
    private const val EXT = ".mp4"

    /** Fixed-width timestamp, so lexicographic sort equals chronological sort. */
    private val NAME_FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** External-media roots, or null when nothing is mounted. */
    private fun externalRoots(context: Context): List<File> =
        runCatching { context.getExternalMediaDirs()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { it != null }

    fun root(context: Context): File {
        val parent = externalRoots(context).firstOrNull { runCatching { it.isDirectory }.getOrDefault(false) }
            ?: File(context.filesDir, "media")
        val dir = File(parent, SUBDIR)
        dir.mkdirs()
        return dir
    }

    /** True when clips survive an uninstall, i.e. they are on external storage. */
    fun isExternal(context: Context): Boolean =
        externalRoots(context).any { runCatching { it.isDirectory }.getOrDefault(false) }

    // "$PREFIX_..." would be read as one identifier (PREFIX_), not PREFIX plus
    // an underscore. Braces or concatenation — never a bare trailing underscore.
    fun newClipName(now: Date = Date()): String =
        PREFIX + "_" + NAME_FMT.format(now) + EXT

    /** Clips on disk, newest first. */
    fun list(context: Context): List<File> {
        val prefix = PREFIX + "_"
        val ext = EXT
        return root(context).listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(ext) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    /**
     * Resolves a client-supplied name to a real file inside the clip directory,
     * or null if the name is not ours or escapes the directory.
     *
     * Name shape is checked before the filesystem is touched, and the canonical
     * path is compared against the canonical directory — checking existence
     * first would let a traversal name probe for files outside the clip folder.
     */
    fun resolve(context: Context, name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        if (!name.startsWith("${PREFIX}_") || !name.endsWith(EXT)) return null
        val base = root(context)
        val file = File(base, name)
        return runCatching {
            if (file.canonicalPath == base.canonicalPath) null
            else if (file.canonicalPath.startsWith(base.canonicalPath + File.separator)) file
            else null
        }.getOrNull()
    }

    fun totalBytes(context: Context): Long =
        list(context).sumOf { it.length() }

    /** Free bytes on the volume holding the clips, via usableSpace. */
    fun usableBytes(context: Context): Long =
        runCatching { root(context).usableSpace }.getOrDefault(0L)
}
