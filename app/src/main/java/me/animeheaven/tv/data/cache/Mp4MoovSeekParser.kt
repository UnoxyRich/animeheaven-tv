package me.animeheaven.tv.data.cache

import kotlin.math.max
import kotlin.math.min

/**
 * Downsampled MP4 keyframe seek point mapping playback timestamp [timeMs] to exact file [byteOffset].
 */
data class Mp4SeekPoint(
    val timeMs: Long,
    val byteOffset: Long
)

/**
 * Persisted video container metadata for an episode, enabling:
 * 1. Cold-start 0 ms `ep:<episodeKey>:<contentLength>` cache key resolution without network probes.
 * 2. Exact keyframe byte-offset targeting for Continue Watching resume & Skip OP preloads.
 */
data class EpisodeVideoIndex(
    val episodeKey: String,
    val contentLength: Long,
    val durationMs: Long = 0L,
    val moovOffset: Long = -1L,
    val moovSize: Long = 0L,
    val seekPoints: List<Mp4SeekPoint> = emptyList(),
    val updatedAtMs: Long = 0L
) {
    val isMoovAtTail: Boolean
        get() = moovOffset > 0L && contentLength > 0L && moovOffset >= contentLength / 2L

    /**
     * Returns the exact keyframe byte offset at or immediately before [targetMs],
     * or null when no seek table was parsed (e.g. MKV or truncated header).
     */
    fun resolveByteOffset(targetMs: Long): Long? {
        if (seekPoints.isEmpty()) return null
        if (targetMs <= 0L) return seekPoints.first().byteOffset
        var lo = 0
        var hi = seekPoints.lastIndex
        var best = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (seekPoints[mid].timeMs <= targetMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return seekPoints[best].byteOffset
    }
}

data class Mp4MoovLocation(
    val offset: Long,
    val size: Long,
    val isAtTail: Boolean
)

/**
 * Pure-Kotlin, zero-dependency ISO-BMFF (MP4) `moov` box locator and video keyframe seek-table
 * parser. Extracts exact `(timeMs -> byteOffset)` mappings from `mvhd`/`mdhd`/`hdlr`/`stts`/
 * `stss`/`stsc`/`stsz`/`stco`/`co64` boxes so resume and Skip OP preloads hit exact keyframes.
 */
object Mp4MoovSeekParser {
    private const val BOX_MOOV = 0x6D6F6F76 // "moov"
    private const val BOX_MDAT = 0x6D646174 // "mdat"
    private const val BOX_MVHD = 0x6D766864 // "mvhd"
    private const val BOX_TRAK = 0x7472616B // "trak"
    private const val BOX_MDIA = 0x6D646961 // "mdia"
    private const val BOX_MDHD = 0x6D646864 // "mdhd"
    private const val BOX_HDLR = 0x68646C72 // "hdlr"
    private const val BOX_MINF = 0x6D696E66 // "minf"
    private const val BOX_STBL = 0x7374626C // "stbl"
    private const val BOX_STTS = 0x73747473 // "stts"
    private const val BOX_STSS = 0x73747373 // "stss"
    private const val BOX_STSC = 0x73747363 // "stsc"
    private const val BOX_STSZ = 0x7374737A // "stsz"
    private const val BOX_STCO = 0x7374636F // "stco"
    private const val BOX_CO64 = 0x636F3634 // "co64"
    private const val HANDLER_VIDE = 0x76696465 // "vide"

    const val MAX_MOOV_PARSE_BYTES = 4L * 1024L * 1024L
    const val DEFAULT_SEEK_INTERVAL_MS = 5_000L
    const val MAX_SEEK_POINTS = 512

    fun containsMp4MoovAtom(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val limit = min(length, bytes.size) - 3
        var i = 0
        while (i < limit) {
            if (bytes[i] == 0x6D.toByte() &&
                bytes[i + 1] == 0x6F.toByte() &&
                bytes[i + 2] == 0x6F.toByte() &&
                bytes[i + 3] == 0x76.toByte()
            ) {
                return true
            }
            i++
        }
        return false
    }

    /**
     * Walks top-level ISO-BMFF boxes in chunk 0 (`headBytes`) to locate `moov` directly or infer
     * a trailing `moov` offset immediately after `mdat` for non-faststart MP4 files.
     */
    fun inspectTopLevelBoxes(
        headBytes: ByteArray,
        headLength: Int = headBytes.size,
        totalLength: Long
    ): Mp4MoovLocation? {
        val limit = min(headLength, headBytes.size)
        if (limit < 8 || totalLength <= 0L) return null

        var offset = 0L
        var boxesVisited = 0
        while (offset + 8L <= limit.toLong() && boxesVisited < 64) {
            val pos = offset.toInt()
            val rawSize = readUInt32(headBytes, pos)
            val boxType = readInt32(headBytes, pos + 4)
            var headerLen = 8L
            val boxSize: Long = when (rawSize) {
                0L -> totalLength - offset
                1L -> {
                    if (pos + 16 > limit) return null
                    headerLen = 16L
                    readInt64(headBytes, pos + 8)
                }
                else -> rawSize
            }
            if (boxSize < headerLen || offset + boxSize > totalLength + 1024L) {
                return null
            }

            if (boxType == BOX_MOOV) {
                val clampedSize = min(boxSize, totalLength - offset)
                return Mp4MoovLocation(
                    offset = offset,
                    size = clampedSize,
                    isAtTail = offset >= totalLength / 2L
                )
            }

            if (boxType == BOX_MDAT) {
                val afterMdat = offset + boxSize
                val tailRemaining = totalLength - afterMdat
                if (afterMdat in 1L until totalLength && tailRemaining in 16L..MAX_MOOV_PARSE_BYTES) {
                    return Mp4MoovLocation(
                        offset = afterMdat,
                        size = tailRemaining,
                        isAtTail = true
                    )
                }
                return null
            }

            offset += boxSize
            boxesVisited++
        }
        return null
    }

    /**
     * Parses a complete `moov` box buffer (which may start directly at `moov` or contain a `moov`
     * box at a known byte offset inside a contiguous chunk slice).
     */
    fun parseMoovIndex(
        episodeKey: String,
        contentLength: Long,
        moovFileOffset: Long,
        buffer: ByteArray,
        bufferLength: Int = buffer.size,
        bufferBaseFileOffset: Long = moovFileOffset,
        sampleIntervalMs: Long = DEFAULT_SEEK_INTERVAL_MS,
        nowMs: Long = System.currentTimeMillis()
    ): EpisodeVideoIndex? {
        val limit = min(bufferLength, buffer.size)
        if (limit < 16 || contentLength <= 0L) return null

        val moovRelStart = (moovFileOffset - bufferBaseFileOffset).toInt()
        if (moovRelStart < 0 || moovRelStart + 8 > limit) return null
        val moovHeader = readBoxHeader(buffer, moovRelStart, limit) ?: return null
        if (moovHeader.type != BOX_MOOV) return null
        val moovEnd = min(limit.toLong(), moovRelStart.toLong() + moovHeader.size).toInt()
        if (moovEnd <= moovHeader.contentStart) return null

        var movieDurationMs = 0L
        var bestTrackDurationMs = 0L
        var bestSeekPoints: List<Mp4SeekPoint> = emptyList()

        forEachChildBox(buffer, moovHeader.contentStart, moovEnd) { box ->
            when (box.type) {
                BOX_MVHD -> {
                    val dur = parseMvhdDurationMs(buffer, box.contentStart, box.end)
                    if (dur > 0L) movieDurationMs = dur
                }
                BOX_TRAK -> {
                    val parsed = parseVideoTrack(buffer, box.contentStart, box.end, sampleIntervalMs)
                    if (parsed != null && parsed.second.size > bestSeekPoints.size) {
                        bestTrackDurationMs = parsed.first
                        bestSeekPoints = parsed.second
                    }
                }
            }
        }

        val resolvedDurationMs = if (movieDurationMs > 0L) movieDurationMs else bestTrackDurationMs
        if (resolvedDurationMs <= 0L && bestSeekPoints.isEmpty()) return null

        return EpisodeVideoIndex(
            episodeKey = episodeKey,
            contentLength = contentLength,
            durationMs = resolvedDurationMs,
            moovOffset = moovFileOffset,
            moovSize = moovHeader.size,
            seekPoints = bestSeekPoints,
            updatedAtMs = nowMs
        )
    }

    private fun parseVideoTrack(
        buf: ByteArray,
        start: Int,
        end: Int,
        sampleIntervalMs: Long
    ): Pair<Long, List<Mp4SeekPoint>>? {
        var mdiaBox: BoxSpan? = null
        forEachChildBox(buf, start, end) { box ->
            if (box.type == BOX_MDIA) mdiaBox = box
        }
        val mdia = mdiaBox ?: return null

        var timescale = 0L
        var durationUnits = 0L
        var isVideo = false
        var minfBox: BoxSpan? = null

        forEachChildBox(buf, mdia.contentStart, mdia.end) { box ->
            when (box.type) {
                BOX_MDHD -> {
                    val (ts, dur) = parseMdhd(buf, box.contentStart, box.end) ?: return@forEachChildBox
                    timescale = ts
                    durationUnits = dur
                }
                BOX_HDLR -> {
                    if (box.contentStart + 12 <= box.end) {
                        val handlerType = readInt32(buf, box.contentStart + 8)
                        isVideo = handlerType == HANDLER_VIDE
                    }
                }
                BOX_MINF -> minfBox = box
            }
        }
        if (!isVideo || timescale <= 0L) return null
        val minf = minfBox ?: return null

        var stblBox: BoxSpan? = null
        forEachChildBox(buf, minf.contentStart, minf.end) { box ->
            if (box.type == BOX_STBL) stblBox = box
        }
        val stbl = stblBox ?: return null

        var sttsBox: BoxSpan? = null
        var stssBox: BoxSpan? = null
        var stscBox: BoxSpan? = null
        var stszBox: BoxSpan? = null
        var stcoBox: BoxSpan? = null
        var co64Box: BoxSpan? = null

        forEachChildBox(buf, stbl.contentStart, stbl.end) { box ->
            when (box.type) {
                BOX_STTS -> sttsBox = box
                BOX_STSS -> stssBox = box
                BOX_STSC -> stscBox = box
                BOX_STSZ -> stszBox = box
                BOX_STCO -> stcoBox = box
                BOX_CO64 -> co64Box = box
            }
        }

        val stts = parseStts(buf, sttsBox ?: return null) ?: return null
        val stsc = parseStsc(buf, stscBox ?: return null) ?: return null
        val stsz = parseStsz(buf, stszBox ?: return null) ?: return null
        val chunkOffsets = co64Box?.let { parseCo64(buf, it) }
            ?: stcoBox?.let { parseStco(buf, it) }
            ?: return null
        if (chunkOffsets.isEmpty()) return null

        val syncSamples = stssBox?.let { parseStss(buf, it) }
        val durationMs = if (durationUnits > 0L) (durationUnits * 1000L) / timescale else 0L
        val seekPoints = buildSeekPoints(
            timescale = timescale,
            sttsCounts = stts.first,
            sttsDeltas = stts.second,
            syncSamples = syncSamples,
            stscFirstChunk = stsc.first,
            stscSamplesPerChunk = stsc.second,
            stszConstantSize = stsz.first,
            stszSizes = stsz.second,
            chunkOffsets = chunkOffsets,
            sampleIntervalMs = sampleIntervalMs
        )
        return durationMs to seekPoints
    }

    private fun buildSeekPoints(
        timescale: Long,
        sttsCounts: IntArray,
        sttsDeltas: LongArray,
        syncSamples: IntArray?,
        stscFirstChunk: IntArray,
        stscSamplesPerChunk: IntArray,
        stszConstantSize: Int,
        stszSizes: IntArray?,
        chunkOffsets: LongArray,
        sampleIntervalMs: Long
    ): List<Mp4SeekPoint> {
        val totalSamples = stszSizes?.size ?: sttsCounts.fold(0) { acc, c -> acc + c }
        if (totalSamples <= 0) return emptyList()

        // Walk chunks and samples simultaneously in a single O(N) pass.
        val result = ArrayList<Mp4SeekPoint>(min(MAX_SEEK_POINTS, 256))
        var sttsIdx = 0
        var sttsRemaining = if (sttsCounts.isNotEmpty()) sttsCounts[0] else 0
        var sttsDelta = if (sttsDeltas.isNotEmpty()) sttsDeltas[0] else 0L

        var syncCursor = 0
        var nextSyncSample = if (syncSamples != null && syncSamples.isNotEmpty()) syncSamples[0] else -1

        var stscIdx = 0
        var sampleIndex1Based = 1
        var currentTimeUnits = 0L
        var lastEmittedTimeMs = Long.MIN_VALUE

        for (chunkIdx in chunkOffsets.indices) {
            val chunkNumber1Based = chunkIdx + 1
            while (stscIdx + 1 < stscFirstChunk.size && stscFirstChunk[stscIdx + 1] <= chunkNumber1Based) {
                stscIdx++
            }
            val samplesInChunk = if (stscIdx < stscSamplesPerChunk.size) stscSamplesPerChunk[stscIdx] else 0
            if (samplesInChunk <= 0) continue

            var byteOffset = chunkOffsets[chunkIdx]
            for (s in 0 until samplesInChunk) {
                if (sampleIndex1Based > totalSamples) break
                val isSync = if (syncSamples == null) {
                    true
                } else {
                    while (syncCursor < syncSamples.size && syncSamples[syncCursor] < sampleIndex1Based) {
                        syncCursor++
                    }
                    nextSyncSample = if (syncCursor < syncSamples.size) syncSamples[syncCursor] else -1
                    nextSyncSample == sampleIndex1Based
                }

                if (isSync) {
                    val timeMs = (currentTimeUnits * 1000L) / timescale
                    val shouldEmit = result.isEmpty() ||
                        (timeMs - lastEmittedTimeMs >= sampleIntervalMs && result.size < MAX_SEEK_POINTS)
                    if (shouldEmit) {
                        result.add(Mp4SeekPoint(timeMs = timeMs, byteOffset = max(0L, byteOffset)))
                        lastEmittedTimeMs = timeMs
                    }
                }

                // Advance byte offset by sample size
                val sampleBytes = if (stszConstantSize > 0) {
                    stszConstantSize
                } else {
                    stszSizes?.getOrNull(sampleIndex1Based - 1) ?: 0
                }
                byteOffset += sampleBytes.toLong()

                // Advance time by stts delta
                while (sttsRemaining <= 0 && sttsIdx + 1 < sttsCounts.size) {
                    sttsIdx++
                    sttsRemaining = sttsCounts[sttsIdx]
                    sttsDelta = sttsDeltas[sttsIdx]
                }
                if (sttsRemaining > 0) {
                    currentTimeUnits += sttsDelta
                    sttsRemaining--
                }
                sampleIndex1Based++
            }
            if (sampleIndex1Based > totalSamples) break
        }
        return result
    }

    private fun parseMvhdDurationMs(buf: ByteArray, start: Int, end: Int): Long {
        if (start + 4 > end) return 0L
        val version = buf[start].toInt() and 0xFF
        return if (version == 0) {
            if (start + 20 > end) return 0L
            val timescale = readUInt32(buf, start + 12)
            val duration = readUInt32(buf, start + 16)
            if (timescale > 0L) (duration * 1000L) / timescale else 0L
        } else if (version == 1) {
            if (start + 32 > end) return 0L
            val timescale = readUInt32(buf, start + 20)
            val duration = readInt64(buf, start + 24)
            if (timescale > 0L && duration > 0L) (duration * 1000L) / timescale else 0L
        } else {
            0L
        }
    }

    private fun parseMdhd(buf: ByteArray, start: Int, end: Int): Pair<Long, Long>? {
        if (start + 4 > end) return null
        val version = buf[start].toInt() and 0xFF
        return if (version == 0) {
            if (start + 20 > end) return null
            readUInt32(buf, start + 12) to readUInt32(buf, start + 16)
        } else if (version == 1) {
            if (start + 32 > end) return null
            readUInt32(buf, start + 20) to readInt64(buf, start + 24)
        } else {
            null
        }
    }

    private fun parseStts(buf: ByteArray, box: BoxSpan): Pair<IntArray, LongArray>? {
        val start = box.contentStart
        if (start + 8 > box.end) return null
        val entryCount = readUInt32(buf, start + 4).toInt()
        if (entryCount <= 0 || entryCount > 500_000 || start + 8L + entryCount * 8L > box.end) return null
        val counts = IntArray(entryCount)
        val deltas = LongArray(entryCount)
        var pos = start + 8
        for (i in 0 until entryCount) {
            counts[i] = readUInt32(buf, pos).toInt()
            deltas[i] = readUInt32(buf, pos + 4)
            pos += 8
        }
        return counts to deltas
    }

    private fun parseStss(buf: ByteArray, box: BoxSpan): IntArray? {
        val start = box.contentStart
        if (start + 8 > box.end) return null
        val entryCount = readUInt32(buf, start + 4).toInt()
        if (entryCount <= 0 || entryCount > 500_000 || start + 8L + entryCount * 4L > box.end) return null
        val out = IntArray(entryCount)
        var pos = start + 8
        for (i in 0 until entryCount) {
            out[i] = readUInt32(buf, pos).toInt()
            pos += 4
        }
        return out
    }

    private fun parseStsc(buf: ByteArray, box: BoxSpan): Pair<IntArray, IntArray>? {
        val start = box.contentStart
        if (start + 8 > box.end) return null
        val entryCount = readUInt32(buf, start + 4).toInt()
        if (entryCount <= 0 || entryCount > 500_000 || start + 8L + entryCount * 12L > box.end) return null
        val firstChunk = IntArray(entryCount)
        val samplesPerChunk = IntArray(entryCount)
        var pos = start + 8
        for (i in 0 until entryCount) {
            firstChunk[i] = readUInt32(buf, pos).toInt()
            samplesPerChunk[i] = readUInt32(buf, pos + 4).toInt()
            pos += 12
        }
        return firstChunk to samplesPerChunk
    }

    private fun parseStsz(buf: ByteArray, box: BoxSpan): Pair<Int, IntArray?>? {
        val start = box.contentStart
        if (start + 12 > box.end) return null
        val constantSize = readUInt32(buf, start + 4).toInt()
        val sampleCount = readUInt32(buf, start + 8).toInt()
        if (sampleCount < 0 || sampleCount > 2_000_000) return null
        if (constantSize > 0) return constantSize to null
        if (start + 12L + sampleCount * 4L > box.end) return null
        val sizes = IntArray(sampleCount)
        var pos = start + 12
        for (i in 0 until sampleCount) {
            sizes[i] = readUInt32(buf, pos).toInt()
            pos += 4
        }
        return 0 to sizes
    }

    private fun parseStco(buf: ByteArray, box: BoxSpan): LongArray? {
        val start = box.contentStart
        if (start + 8 > box.end) return null
        val entryCount = readUInt32(buf, start + 4).toInt()
        if (entryCount <= 0 || entryCount > 500_000 || start + 8L + entryCount * 4L > box.end) return null
        val offsets = LongArray(entryCount)
        var pos = start + 8
        for (i in 0 until entryCount) {
            offsets[i] = readUInt32(buf, pos)
            pos += 4
        }
        return offsets
    }

    private fun parseCo64(buf: ByteArray, box: BoxSpan): LongArray? {
        val start = box.contentStart
        if (start + 8 > box.end) return null
        val entryCount = readUInt32(buf, start + 4).toInt()
        if (entryCount <= 0 || entryCount > 500_000 || start + 8L + entryCount * 8L > box.end) return null
        val offsets = LongArray(entryCount)
        var pos = start + 8
        for (i in 0 until entryCount) {
            offsets[i] = readInt64(buf, pos)
            pos += 8
        }
        return offsets
    }

    private data class BoxSpan(
        val type: Int,
        val start: Int,
        val contentStart: Int,
        val end: Int,
        val size: Long
    )

    private fun readBoxHeader(buf: ByteArray, pos: Int, limit: Int): BoxSpan? {
        if (pos + 8 > limit) return null
        val rawSize = readUInt32(buf, pos)
        val type = readInt32(buf, pos + 4)
        var headerLen = 8
        val size: Long = when (rawSize) {
            0L -> (limit - pos).toLong()
            1L -> {
                if (pos + 16 > limit) return null
                headerLen = 16
                readInt64(buf, pos + 8)
            }
            else -> rawSize
        }
        if (size < headerLen.toLong()) return null
        val end = min(limit.toLong(), pos.toLong() + size).toInt()
        return BoxSpan(type, pos, pos + headerLen, end, size)
    }

    private inline fun forEachChildBox(
        buf: ByteArray,
        start: Int,
        end: Int,
        visitor: (BoxSpan) -> Unit
    ) {
        var pos = start
        var guard = 0
        while (pos + 8 <= end && guard < 2048) {
            val box = readBoxHeader(buf, pos, end) ?: break
            visitor(box)
            if (box.end <= pos) break
            pos = box.end
            guard++
        }
    }

    private fun readInt32(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or
            ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or
            (buf[pos + 3].toInt() and 0xFF)

    private fun readUInt32(buf: ByteArray, pos: Int): Long =
        readInt32(buf, pos).toLong() and 0xFFFFFFFFL

    private fun readInt64(buf: ByteArray, pos: Int): Long =
        ((readUInt32(buf, pos)) shl 32) or readUInt32(buf, pos + 4)
}
