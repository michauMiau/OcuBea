package com.ocubea.security

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The path guard on a security camera's clip store.
 *
 * `resolve()` is the only thing standing between a `DELETE /clips/<name>`
 * request and `File.delete()` on the phone. It is pure `java.io.File`, so it
 * runs on the JVM; the only Android piece is the `Context` it reads the clip
 * directory from, which is satisfied here by a `ContextWrapper` over a temp
 * folder.
 *
 * The names below are the ones a client can actually send. There are three
 * call sites in `StreamServer` and they differ:
 *
 *  - `clipsDeleteOne` and `serveClip` take the name straight off the URI path
 *    (`uri.removePrefix("/clips").trimStart('/').substringBefore('?')`), so it
 *    is whatever the client typed, still URL-encoded. `klip_..%2F..%2Fetc.mp4`
 *    arrives with the percent-encoding intact and is only decoded later, if ever.
 *  - `clipsDeleteMany` first runs the raw body through the pattern
 *    `klip_[A-Za-z0-9_.\-]+\.mp4`, so by the time it reaches `resolve()` the
 *    name is already restricted to that character class.
 *
 * A traversal string survives that pattern — `..` and `%` … `%` is not in the
 * class, but `klip_....mp4` and `klip_a...b.mp4` are — which is why the guard
 * inside `resolve()` is the one being tested here rather than the caller's
 * regex.
 */
class ClipStorageResolveTest {

    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private var folderSeq = 0

    /**
     * A phone with no SD card: `getExternalMediaDirs()` is empty, so clips
     * fall back to filesDir. `TemporaryFolder` refuses to create the same
     * folder name twice, and several tests build more than one context.
     */
    private fun internalContext(): Pair<Context, File> {
        val files = tmp.newFolder("appfiles-${folderSeq++}")
        val ctx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
            override fun getExternalMediaDirs(): Array<File> = arrayOf()
        }
        return ctx to files
    }

    /** The same, with a mounted external volume, which is the usual case. */
    private fun externalContext(): Pair<Context, File> {
        val files = tmp.newFolder("appfiles-${folderSeq++}")
        val media = tmp.newFolder("Android-media-${folderSeq++}")
        val ctx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
            override fun getExternalMediaDirs(): Array<File> = arrayOf(media)
        }
        return ctx to media
    }

    /**
     * True when [f] does not sit directly inside [ctx]'s clip directory.
     *
     * This is the property the guard exists to guarantee, read back from the
     * file the production code actually returned rather than re-derived from
     * the rules it is supposed to follow.
     */
    private fun escapesClipDir(ctx: Context, f: File): Boolean =
        f.canonicalFile.parentFile?.canonicalFile != ClipStorage.root(ctx).canonicalFile

    // ── the rejection path ─────────────────────────────────────

    @Test
    fun `a dot-dot name cannot climb out of the clip directory`() {
        val (ctx, _) = internalContext()
        // The primary attack. The name keeps the clip prefix and extension, so
        // it is shaped exactly like a legitimate clip and only the shape check
        // stops it.
        for (name in listOf(
            "klip_../../../etc/passwd.mp4",
            "klip_..%2F..%2Fetc%2Fpasswd.mp4",
            "klip_..\\..\\win.mp4",
            "klip_....mp4",
            "klip_a...b.mp4",
            "klip_..mp4",
        )) {
            val resolved = ClipStorage.resolve(ctx, name)
            assertNull(
                "a name containing '..' must never resolve: resolve() accepted " +
                    "[$name] and pointed at ${resolved?.canonicalPath}. On a " +
                    "DELETE that is an arbitrary file delete on the phone.",
                resolved,
            )
        }
    }

    @Test
    fun `a name with a path separator is refused rather than treated as a path`() {
        // `File(parent, "a/b")` silently becomes parent/a/b, so a name that
        // carries a separator is a directory reference, not a file name.
        val (ctx, _) = internalContext()
        for (name in listOf(
            "klip_/../etc/passwd.mp4",
            "klip_sub/clip.mp4",
            "/etc/passwd",
            "/klip_etc_passwd.mp4",
            "..",
            ".",
        )) {
            val resolved = ClipStorage.resolve(ctx, name)
            assertNull(
                "a name containing a path separator must not resolve: the " +
                    "resulting File is outside the clip directory by " +
                    "construction, got ${resolved?.canonicalPath}",
                resolved,
            )
        }
    }

    @Test
    fun `a name that is not a clip is refused`() {
        // The name is echoed into a 404 body and used to build a
        // Content-Disposition filename, so anything that is not a clip name is
        // a value the app has no business acting on.
        val (ctx, _) = internalContext()
        for (name in listOf(
            "passwd",
            "config.xml",
            "klip_",
            "clip_2026-01-02.mp4",
            "klip_a.mp4.bak",
            "klip_a.MP4",
            "klip_a.mp4 ",
        )) {
            assertNull(
                "only names this app created may resolve: the prefix and the " +
                    "extension are what identify a clip, and [$name] has " +
                    "neither",
                ClipStorage.resolve(ctx, name),
            )
        }
    }

    /**
     * An empty name is the one input where the prefix check and the
     * empty-name check overlap, so this test cannot tell them apart — deleting
     * either one leaves the other refusing `""`. Stated here so nobody later
     * reads this as "the isEmpty() check is covered" and drops it: the two
     * guards are individually redundant, and the reason they are not is
     * visible below.
     *
     * What is actually load-bearing is that an empty name never reaches
     * `File(base, name)`, because `File(dir, "")` canonicalises to the clip
     * DIRECTORY and `clipsDeleteOne` would then be answering a request to
     * delete the whole clip folder. The context below points at a clip
     * directory that does not exist, so nothing downstream can rescue it.
     */
    @Test
    fun `an empty name is refused`() {
        val files = tmp.newFolder("appfiles-${folderSeq++}")
        val ctx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = files
            override fun getExternalMediaDirs(): Array<File> = arrayOf()
        }
        assertNull(
            "an empty name must never resolve; File(dir, \"\") is the " +
                "directory, and deleting that is not a thing the app may do",
            ClipStorage.resolve(ctx, ""),
        )
    }

    /**
     * A backslash is a path separator on Windows and nothing at all on Linux,
     * which is exactly why it is checked explicitly: the guard is the only
     * thing standing between a name that is inert on the test machine and the
     * same name being a directory reference on a device, a backup tool or a
     * share that translates separators.
     *
     * Nothing else in the name is unusual — right prefix, right extension, no
     * dot-dot — so only the backslash check can refuse it.
     */
    @Test
    fun `a name containing a backslash is refused`() {
        val (ctx, _) = internalContext()
        val name = "klip_a\\b.mp4"
        assertNull(
            "on Linux a backslash is an ordinary filename character, so only " +
                "the explicit check refuses a name that is a directory " +
                "reference wherever separators are translated",
            ClipStorage.resolve(ctx, name),
        )
    }

    @Test
    fun `a name carrying a NUL byte never resolves`() {
        // A NUL is the one character that can truncate a path at the syscall
        // boundary on some platforms, so the name the guard checked must never
        // become a path the filesystem sees.
        val (ctx, _) = internalContext()
        val resolved = try {
            ClipStorage.resolve(ctx, "klip_a\u0000.mp4")
        } catch (e: Throwable) {
            null
        }
        assertNull(
            "a NUL in the name must be refused rather than reach the " +
                "filesystem, got ${resolved?.canonicalPath}",
            resolved,
        )
    }

    /**
     * The real defence is the canonical-path comparison, not the string checks
     * above. This is the only test that proves it: a symlink inside the clip
     * directory pointing at a file outside it passes every name-shape rule —
     * right prefix, right extension, no dot-dot, no separator — and only the
     * canonical comparison refuses it.
     */
    @Test
    fun `a symlink planted inside the clip directory cannot escape it`() {
        val (ctx, _) = internalContext()
        val outside = tmp.newFile("secret-${folderSeq++}.txt")
        outside.writeText("not a clip")
        val link = File(ClipStorage.root(ctx), ClipStorage.newClipName())
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        }
        org.junit.Assume.assumeTrue(
            "this filesystem does not support symlinks", linked.isSuccess,
        )

        val resolved = ClipStorage.resolve(ctx, link.name)
        assertNull(
            "a symlink to ${outside.canonicalPath} passes every name-shape " +
                "check but resolves outside the clip directory, so serving or " +
                "deleting it is arbitrary file access",
            resolved,
        )
    }

    /**
     * The canonical comparison has to include the directory separator, and the
     * case that proves it is a SIBLING directory whose name starts with the
     * clip directory's name.
     *
     * `startsWith` on a bare path prefix is the classic escape: with the
     * clip directory at `.../media/klipy`, a file at `.../media/klipy-evil/x`
     * satisfies `path.startsWith(".../media/klipy")` even though it is a
     * different directory entirely. Nothing about the name can produce that —
     * no `..`, no separator — so a symlink is the only way in, and a symlink
     * is exactly what a compromised or careless app with storage access would
     * leave behind.
     */
    @Test
    fun `a sibling directory whose name extends the clip directory is not inside it`() {
        val (ctx, _) = internalContext()
        val clipRoot = ClipStorage.root(ctx).canonicalFile
        val sibling = File(clipRoot.parentFile, clipRoot.name + "-evil")
        sibling.mkdirs()
        val secret = File(sibling, "secret.mp4")
        secret.writeBytes(byteArrayOf(1, 2, 3))

        val link = File(clipRoot, ClipStorage.newClipName())
        val linked = runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        }
        org.junit.Assume.assumeTrue(
            "this filesystem does not support symlinks", linked.isSuccess,
        )

        assertTrue(
            "the test is only meaningful if the sibling really does share the " +
                "clip directory's name prefix: $secret",
            secret.canonicalPath.startsWith(clipRoot.path) &&
                !secret.canonicalPath.startsWith(clipRoot.path + File.separator),
        )
        assertNull(
            "a file under ${clipRoot.name}-evil matches a bare startsWith on " +
                "the clip directory, so the separator after it is the only " +
                "thing refusing this. Dropping it turns a planted symlink " +
                "into arbitrary file read and delete.",
            ClipStorage.resolve(ctx, link.name),
        )
    }

    // ── the accept path ────────────────────────────────────────

    @Test
    fun `a real clip name resolves to a file inside the clip directory`() {
        val (ctx, _) = internalContext()
        val name = ClipStorage.newClipName()
        val resolved = ClipStorage.resolve(ctx, name)
        assertNotNull("a clip this app just named must resolve", resolved)
        assertEquals(name, resolved!!.name)
        assertTrue(
            "the resolved file must sit directly in the clip directory, not " +
                "below it: ${resolved.canonicalPath}",
            !escapesClipDir(ctx, resolved),
        )
    }

    /**
     * Round trip: a name produced by `newClipName()` has to be accepted by
     * `resolve()` and has to show up in `list()`.
     *
     * These two functions define the clip naming scheme between them. A change
     * to the timestamp pattern that introduces a character `resolve()` rejects
     * would make every clip unreachable while every listing still looked
     * correct — the WebUI would list clips that 400 on download.
     */
    @Test
    fun `every name the app generates is one the app can resolve`() {
        val (ctx, _) = internalContext()
        val names = listOf(
            ClipStorage.newClipName(java.util.Date(0L)),
            ClipStorage.newClipName(java.util.Date(1_700_000_000_000L)),
            ClipStorage.newClipName(),
        )
        for (name in names) {
            val f = ClipStorage.resolve(ctx, name)
            assertNotNull("resolve() rejected a name newClipName() produced: $name", f)
            f!!.writeBytes(byteArrayOf(1, 2, 3))
        }
        assertEquals(
            "a clip that resolve() accepts must also be listable, otherwise " +
                "the WebUI lists clips it will not serve",
            names.sorted(), ClipStorage.list(ctx).map { it.name }.sorted(),
        )
    }

    /**
     * Names sort chronologically. The listing is what the UI shows and the
     * retention sweep walks the same files, so lexicographic order has to be
     * time order — a name whose timestamp varied in width would interleave
     * years in the list.
     */
    @Test
    fun `generated names are fixed width so a lexicographic sort is chronological`() {
        val old = ClipStorage.newClipName(java.util.Date(0L))
        val recent = ClipStorage.newClipName()
        assertEquals(
            "clip names must all be the same length, or sorting them by name " +
                "stops meaning sorting them by time",
            old.length, recent.length,
        )
        assertTrue("newer clips must sort after older ones: '$old' vs '$recent'", old < recent)
    }

    /**
     * The client contract, as the WebUI performs it.
     *
     * `clipsIndex` hands out `File.name` for every clip and the UI sends that
     * string back verbatim in `DELETE /clips/<name>` and `GET /clips/<name>`.
     * A generated name has to survive that round trip untouched, or the clip
     * it names cannot be deleted or played over the API.
     */
    @Test
    fun `a name survives the round trip the index and the client perform`() {
        val (ctx, _) = internalContext()
        val name = ClipStorage.newClipName()
        File(ClipStorage.root(ctx), name).writeBytes(byteArrayOf(7))
        val fromIndex = ClipStorage.list(ctx).single().name
        assertEquals("the index must hand out the file name unchanged", name, fromIndex)
        assertEquals(
            "a generated name must need no percent-encoding on the way back, " +
                "or the server decodes it into a different string than the one " +
                "resolve() guards",
            name, java.net.URLDecoder.decode(java.net.URLEncoder.encode(name, "UTF-8"), "UTF-8"),
        )
        assertNotNull(
            "the name the client sends back must resolve",
            ClipStorage.resolve(ctx, java.net.URLDecoder.decode(fromIndex, "UTF-8")),
        )
    }

    // ── the directory the guard is relative to ──────────────────

    /**
     * With an external volume mounted the clips live there, and the guard has
     * to be relative to THAT directory. A guard comparing against `filesDir`
     * would pass every test above and still be wrong on the configuration
     * that actually ships.
     */
    @Test
    fun `the guard follows the clip directory onto external storage`() {
        val (ctx, media) = externalContext()
        assertTrue(
            "clips belong under Android/media so the gallery can see them, " +
                "but the clip directory resolved to " +
                "${ClipStorage.root(ctx).canonicalPath}",
            ClipStorage.root(ctx).canonicalFile.parentFile == media.canonicalFile,
        )
        assertTrue(
            "with an external volume mounted isExternal() must be true — the " +
                "UI reports this to warn that clips survive an uninstall",
            ClipStorage.isExternal(ctx),
        )
        val resolved = ClipStorage.resolve(ctx, ClipStorage.newClipName())
        assertNotNull("a clip on external storage must still resolve", resolved)
        assertTrue(
            "the guard must be relative to the external clip directory, not " +
                "to filesDir: ${resolved!!.canonicalPath}",
            !escapesClipDir(ctx, resolved),
        )
    }
}
