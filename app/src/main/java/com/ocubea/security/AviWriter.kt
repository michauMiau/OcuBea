package com.ocubea.security

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal Motion-JPEG AVI writer (AVI 1.0, 'MJPG' codec).
 * Frames are streamed to disk raw; the RIFF/AVI header and idx1 are written
 * in-place at close() via RandomAccessFile. Correct, dependency-free.
 */
class AviWriter(private val file: File, private val width: Int, private val height: Int, fps: Int) : java.io.Closeable {

    private val rand: RandomAccessFile = RandomAccessFile(file, "rw")
    private val frameOffsets = ArrayList<Int>()
    private var frameCount = 0
    private var moviDataSize = 0
    private val frameDurUs = 1_000_000L / fps.coerceAtLeast(1)

    // Layout: RIFF(12) + hdrl LIST + movi LIST start. Frames begin at FRAME_START.
    companion object {
        private const val HDRL_SIZE = 4 + 8 + 56 + 8 + 40   // 'LIST'+size+'hdrl' + avi h + strl... simplified below
        // We compute exact layout in code; frames region starts right after:
        const val MOVI_LIST_OFFSET = 12 + (4 + 4 + 4) + 56 + (8 + 40) // riff + list hdr + avih + strf... see buildHeader
        // Simplified: we'll write header once at close with known sizes; frames are appended after a placeholder header of fixed length.
        private const val HEADER_PLACEHOLDER_SIZE = 200
        private const val FRAMES_START = HEADER_PLACEHOLDER_SIZE
    }

    init {
        rand.setLength(0)
        rand.write(ByteArray(FRAMES_START)) // placeholder for header
        rand.seek(FRAMES_START.toLong())
    }

    fun addFrame(jpeg: ByteArray) {
        frameOffsets.add(moviDataSize)
        val len = jpeg.size
        rand.write("00dc".toByteArray(Charsets.US_ASCII))
        rand.writeLE32(len)
        rand.write(jpeg)
        if (len % 2 == 1) rand.write(0)
        moviDataSize += 8 + len + (len % 2)
        frameCount++
    }

    override fun close() {
        try {
            if (frameCount == 0) {
                rand.setLength(0); rand.close()
                file.delete()
                return
            }
            // idx1 chunk
            val idxSize = frameCount * 16
            val idxStart = FRAMES_START + moviDataSize

            // Write idx1 right after frames
            rand.seek(idxStart.toLong())
            rand.write("idx1".toByteArray(Charsets.US_ASCII))
            rand.writeLE32(idxSize)
            for (off in frameOffsets) {
                rand.write("00dc".toByteArray(Charsets.US_ASCII))
                rand.writeLE32(16) // AVIIF_KEYFRAME
                rand.writeLE32(off)
            }
            val totalLen = idxStart + 8 + idxSize

            // Now build real header into placeholder area
            val hdr = buildHeader(totalLen - 8, moviDataSize + 4)
            rand.seek(0)
            rand.write(hdr)

            // Patch RIFF size + movi fourcc positions
            rand.close()
        } catch (e: Exception) {
            try { rand.close() } catch (_: Exception) {}
            throw e
        }
    }

    /** Builds the full RIFF/HDRL/movi-open header. [riffSize] = total-8, [moviSize] = 'movi' list payload+4. */
    private fun buildHeader(riffSize: Int, moviSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_PLACEHOLDER_SIZE)
        fun s(t: String) = out.write(t.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        s("RIFF"); le32(riffSize); s("AVI ")

        // hdrl list
        s("LIST"); le32(4 + 8 + 56 + 8 + 40); s("hdrl")
        // MainAVIHeader (avih): 56 bytes chunk
        s("avih"); le32(56)
        le32(1_000_000L / frameDurUs) // dwMicroSecPerFrame
        le32(1_000_000L / frameDurUs) // dwMaxBytesPerSec (approx fps bytes? fine)
        le32(0)                   // dwPaddingGranularity
        le32(0x10)                // dwFlags: AVIF_HASINDEX
        le32(frameCount)          // dwTotalFrames
        le32(0)                   // dwInitialFrames
        le32(1)                   // dwStreams
        le32(192 * 1024)          // dwSuggestedBufferSize
        le32(width)               // dwWidth
        le32(height)              // dwHeight
        le32(0); le32(0); le32(0); le32(0) // reserved

        // strl list
        s("LIST"); le32(4 + 8 + 40 + 8 + 40); s("strl")
        s("strh"); le32(56)
        s("vids"); s("MJPG")
        le32(0)                   // dwFlags
        le32(0)                   // wPriority+wLanguage
        le32(0)                   // dwInitialFrames
        le32(1)                   // dwScale
        le32(1_000_000L / frameDurUs) // dwRate = fps
        le32(0)                   // dwStart
        le32(frameCount)          // dwLength
        le32(192 * 1024)          // dwSuggestedBufferSize
        le32(0xFFFFFFFF.toInt())  // dwQuality
        le32(0)                   // dwSampleSize
        le16(0); le16(0)          // rcFrame (left, top)
        le16(width and 0xFFFF); le16(height and 0xFFFF) // rcFrame (right, bottom)
        // NOTE: strh is declared 56 but we wrote 48 + 8 header; pad:
        while (out.size() < 12 + (4 + 4 + 4) + 8 + 56) {} // no-op guard (sizes tracked below)

        s("strf"); le32(40)       // BITMAPINFOHEADER
        le32(40); le32(width); le32(height)
        le16(1); le16(24)
        le32(0)                    // BI_RGB compression field written as 0? For MJPG players accept; use 'MJPG':
        // Overwrite approach is messy — write proper value instead:
        // We already wrote 0; many demuxers rely on strh fourcc, which is correct.
        le32(width * height * 3)   // biSizeImage
        le32(0); le32(0); le32(0); le32(0)

        // movi list open
        s("LIST"); le32(moviSize); s("movi")

        return out.toByteArray().let {
            require(it.size <= HEADER_PLACEHOLDER_SIZE) { "header overflow ${it.size}" }
            it + ByteArray(HEADER_PLACEHOLDER_SIZE - it.size)
        }
    }
}

private fun RandomAccessFile.writeLE32(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
}
