package com.ocubea.security

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal Motion-JPEG AVI writer (AVI 1.0, 'MJPG' codec).
 *
 * Frames are streamed to disk raw; the RIFF/AVI header and idx1 are written
 * in-place at close() via RandomAccessFile, so nothing has to be buffered in
 * memory for the length of a recording.
 *
 * Pure java.io, so AviWriterTest exercises it on the JVM. That is deliberate:
 * this class was silently broken - the header did not fit the placeholder the
 * frame offsets were measured from, so every close() with a frame in it threw -
 * and neither a build nor lint can see that.
 */
class AviWriter(private val file: File, private val width: Int, private val height: Int, fps: Int) : java.io.Closeable {

    private val rand: RandomAccessFile = RandomAccessFile(file, "rw")
    private val frameOffsets = ArrayList<Int>()
    private var frameCount = 0
    private var moviDataSize = 0
    private val frameDurUs = 1_000_000L / fps.coerceAtLeast(1)

    // Layout: RIFF(12) + hdrl LIST + movi LIST start. Frames begin at FRAME_START.
    //
    // The placeholder must cover the header EXACTLY, because every frame
    // offset written into idx1 is measured from it. The size below is not a
    // guess: it is the byte count of buildHeader(), which is a fixed sequence
    // of fourcc tags and fixed-width fields - only the values inside them vary
    // with width, height and frame count. AviWriterTest measures the real
    // header and fails if these two ever disagree again.
    companion object {
        /**
         * 224 bytes: RIFF(12) + hdrl LIST(12) + avih(8+56) + strl LIST(12)
         * + strh(8+56) + strf(8+40) + movi LIST(12).
         */
        const val HEADER_PLACEHOLDER_SIZE = 224
        const val FRAMES_START = HEADER_PLACEHOLDER_SIZE

        /** biCompression for JPEG-in-AVI: the 'MJPG' fourcc, little-endian. */
        private const val MJPG_FOURCC = 0x47504A4D
    }

    init {
        rand.setLength(0)
        rand.write(ByteArray(FRAMES_START)) // placeholder for header
        rand.seek(FRAMES_START.toLong())
    }

    fun addFrame(jpeg: ByteArray) {
        // idx1 offsets are relative to the start of the 'movi' list, which
        // begins with the 4-byte 'movi' fourcc that buildHeader writes as the
        // last bytes of the header. The first frame is therefore at offset 4.
        frameOffsets.add(4 + moviDataSize)
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
            // The 'movi' fourcc is the last 4 bytes of the header, written by
            // buildHeader into the placeholder, so the first frame already
            // starts at FRAMES_START and idx1 goes straight after the frames.
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

            // Now build real header into placeholder area.
            // moviSize is the payload of the movi LIST, which is the 'movi'
            // fourcc plus every frame chunk. Offsets below are measured from
            // the fourcc, so the sizes have to agree or idx1 points at garbage.
            val moviPayload = 4 + moviDataSize
            val hdr = buildHeader(totalLen - 8, moviPayload)
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
        le32((1_000_000L / frameDurUs).toInt()) // dwMicroSecPerFrame
        le32((1_000_000L / frameDurUs).toInt()) // dwMaxBytesPerSec (approx fps bytes? fine)
        le32(0)                   // dwPaddingGranularity
        le32(0x10)                // dwFlags: AVIF_HASINDEX
        le32(frameCount)          // dwTotalFrames
        le32(0)                   // dwInitialFrames
        le32(1)                   // dwStreams
        le32(192 * 1024)          // dwSuggestedBufferSize
        le32(width)               // dwWidth
        le32(height)              // dwHeight
        le32(0); le32(0); le32(0); le32(0) // reserved

        // strl list. Stream header: 'strh' is declared 56 bytes and padded to
        // that, because a player reading the declared size will seek past
        // whatever we actually wrote.
        s("LIST"); le32(4 + 8 + 56 + 8 + 40); s("strl")
        s("strh"); le32(56)
        s("vids"); s("MJPG")
        le32(0)                   // dwFlags
        le32(0)                   // wPriority + wLanguage
        le32(0)                   // dwInitialFrames
        le32(1)                   // dwScale
        le32((1_000_000L / frameDurUs).toInt()) // dwRate = fps
        le32(0)                   // dwStart
        le32(frameCount)          // dwLength
        le32(192 * 1024)          // dwSuggestedBufferSize
        le32(0xFFFFFFFF.toInt())  // dwQuality
        le32(0)                   // dwSampleSize
        // rcFrame: left, top, right, bottom - four le16, 8 bytes.
        le16(0); le16(0)
        le16(width and 0xFFFF); le16(height and 0xFFFF)
        // 'strh' payload: 8 ('vids'+'MJPG') + 40 (ten le32) + 8 (rcFrame)
        // = 56, which is exactly what the chunk declared. No padding, and
        // the header length is therefore fully determined.

        s("strf"); le32(40)       // BITMAPINFOHEADER
        le32(40); le32(width); le32(height)
        le16(1); le16(24)
        // biCompression: 'MJPG' as a fourcc. Written as 0 (BI_RGB) this file
        // claimed raw pixels while carrying JPEG frames, which is how a player
        // ends up showing noise instead of a picture.
        le32(MJPG_FOURCC)          // biCompression
        le32(width * height * 3)   // biSizeImage
        le32(0); le32(0); le32(0); le32(0)

        // movi list open
        s("LIST"); le32(moviSize); s("movi")

        // Fail loudly and specifically. The generic version of this guard
        // threw "header overflow 224" for every clip ever recorded, and the
        // message said nothing about which two numbers disagreed.
        val hdr = out.toByteArray()
        require(hdr.size == HEADER_PLACEHOLDER_SIZE) {
            "header is ${hdr.size} bytes but frames start at $HEADER_PLACEHOLDER_SIZE; " +
                "the two must match or every idx1 offset points into the header"
        }
        return hdr
    }
}

private fun RandomAccessFile.writeLE32(v: Int) {
    write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
}
