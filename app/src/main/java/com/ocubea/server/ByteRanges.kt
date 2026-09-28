package com.ocubea.server

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Byte-range file responses.
 *
 * newFixedLengthResponse() cannot express 206 Partial Content, and without Range
 * support a browser will not render a seek bar for a clip and cannot resume a
 * download — it would have to buffer the whole file first. NanoHTTPD's Response
 * is not final, so the pieces needed (setData, setMimeType, addHeader,
 * setKeepAlive) are all reachable from a subclass.
 */
object ByteRanges {

    const val MIME_MP4 = "video/mp4"

    data class Request(val start: Long, val end: Long, val partial: Boolean)

    /**
     * Parses a single-range `Range: bytes=a-b` header against a known length.
     *
     * Only the first range of a multi-range request is honoured: that is legal
     * per RFC 9110 and avoids multipart/byteranges, which no media element
     * actually asks for. An unsatisfiable range yields null so the caller can
     * answer 416 rather than silently serving the whole file.
     */
    fun parse(header: String?, total: Long): Request? {
        if (header.isNullOrBlank() || total <= 0) return null
        if (!header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null

        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()
        return try {
            if (startText.isEmpty()) {
                // "-N": the final N bytes.
                val suffix = endText.toLong()
                if (suffix <= 0) null
                else Request((total - suffix).coerceAtLeast(0), total - 1, true)
            } else {
                val start = startText.toLong()
                // `return`, not a bare `null`. As an expression the null was
                // discarded and control fell through to the coerceIn below,
                // where start > total - 1 made it throw - so a seek past the
                // end of a clip came back as HTTP 500 instead of a 416.
                if (start < 0 || start >= total) return null
                val end = if (endText.isEmpty()) total - 1
                          else endText.toLong().coerceIn(start, total - 1)
                Request(start, end, true)
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Builds a 200 or 206 response for [file]. Sets Accept-Ranges so the client
     * knows seeking is possible at all.
     *
     * NanoHTTPD's Response constructor is protected, so a bare
     * `NanoHTTPD.Response(...)` will not compile from here — the object has to
     * come from a factory on the server. newFixedLengthResponse is that factory,
     * and passing the fully-bounded stream as its "newInputStream" gives the
     * right Content-Length for free; the 206-specific Content-Range and status
     * are then overridden on the returned object.
     */
    fun respond(
        file: File,
        rangeHeader: String?,
        newFixedLength: (Status, String, InputStream, Long) -> NanoHTTPD.Response,
        mime: String = MIME_MP4,
    ): NanoHTTPD.Response {
        val total = file.length()
        val request = parse(rangeHeader, total)

        if (request == null) {
            val res = newFixedLength(Status.OK, mime, FileInputStream(file), total)
            res.addHeader("Accept-Ranges", "bytes")
            res.addHeader("Cache-Control", "no-cache")
            return res
        }

        val length = request.end - request.start + 1
        val stream = BoundedInputStream(FileInputStream(file), request.start, length)
        val res = newFixedLength(Status.PARTIAL_CONTENT, mime, stream, length)
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Cache-Control", "no-cache")
        res.addHeader("Content-Range", "bytes ${request.start}-${request.end}/$total")
        return res
    }

    /**
     * Clips a stream to [length] bytes starting at [start], and does not read
     * past the end even if the caller keeps pulling — the underlying file may
     * shrink under us when a retention sweep deletes it mid-download.
     */
    private class BoundedInputStream(
        private val source: InputStream,
        private val start: Long,
        private val length: Long,
    ) : InputStream() {
        private var position = start
        private var remaining = length

        override fun read(): Int {
            if (remaining <= 0) return -1
            if (position > 0) {
                var toSkip = position
                while (toSkip > 0) {
                    val s = source.skip(toSkip)
                    if (s <= 0) break
                    toSkip -= s
                }
                position = 0
            }
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (remaining <= 0) return -1
            if (position > 0) {
                var toSkip = position
                while (toSkip > 0) {
                    val s = source.skip(toSkip)
                    if (s <= 0) break
                    toSkip -= s
                }
                position = 0
            }
            val want = minOf(len.toLong(), remaining).toInt()
            val n = source.read(b, off, want)
            if (n > 0) remaining -= n
            return n
        }

        override fun close() = source.close()
    }
}
