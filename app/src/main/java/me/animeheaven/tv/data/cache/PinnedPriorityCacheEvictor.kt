package me.animeheaven.tv.data.cache

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Two-tier Media3 [CacheEvictor] that protects preloaded hot-start, Continue Watching resume,
 * and Watch Next byte windows (Tier A, up to [protectedRatio] of [maxBytes]) from being evicted
 * by watched full-episode streaming history (Tier B).
 */
@OptIn(UnstableApi::class)
class PinnedPriorityCacheEvictor(
    private val maxBytes: Long,
    protectedRatio: Double = 0.40
) : CacheEvictor {

    private val protectedBudgetBytes: Long =
        (maxBytes.coerceAtLeast(0L) * protectedRatio.coerceIn(0.1, 0.8)).toLong()

    private val spanComparator = Comparator<CacheSpan> { lhs, rhs ->
        val d = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        if (d == 0L) lhs.compareTo(rhs) else if (d < 0L) -1 else 1
    }

    private val lock = Any()
    private val protectedSpans = TreeSet(spanComparator)
    private val evictableSpans = TreeSet(spanComparator)
    private val hotRangesByKey = HashMap<String, MutableList<LongRange>>()

    private var protectedBytes: Long = 0L
    private var evictableBytes: Long = 0L

    val totalTrackedBytes: Long
        get() = synchronized(lock) { protectedBytes + evictableBytes }

    val protectedTrackedBytes: Long
        get() = synchronized(lock) { protectedBytes }

    val evictableTrackedBytes: Long
        get() = synchronized(lock) { evictableBytes }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (isSpanProtectedLocked(span.key, span.position, span.length)) {
                if (protectedSpans.add(span)) {
                    protectedBytes += span.length
                }
            } else {
                if (evictableSpans.add(span)) {
                    evictableBytes += span.length
                }
            }
        }
        evictCache(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            if (protectedSpans.remove(span)) {
                protectedBytes = (protectedBytes - span.length).coerceAtLeast(0L)
            } else if (evictableSpans.remove(span)) {
                evictableBytes = (evictableBytes - span.length).coerceAtLeast(0L)
            }
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /**
     * Pins `[startByte, endByteExclusive)` of [cacheKey] into Tier A so watching full episodes
     * will evict watched Tier B spans before touching this hot window.
     */
    fun markHotWindow(cacheKey: String, startByte: Long, endByteExclusive: Long) {
        if (cacheKey.isBlank() || endByteExclusive <= startByte) return
        val clampedStart = startByte.coerceAtLeast(0L)
        val newRange = clampedStart until endByteExclusive
        synchronized(lock) {
            val ranges = hotRangesByKey.getOrPut(cacheKey) { ArrayList(3) }
            ranges.add(newRange)
            mergeRangesInPlace(ranges)
            if (ranges.size > MAX_RANGES_PER_KEY) {
                ranges.subList(0, ranges.size - MAX_RANGES_PER_KEY).clear()
            }
            if (hotRangesByKey.size > MAX_PINNED_KEYS) {
                val oldestKey = hotRangesByKey.keys.firstOrNull { it != cacheKey }
                if (oldestKey != null) unpinKeyLocked(oldestKey)
            }

            // Promote any already-cached spans in evictableSpans that fall within the new range
            val toPromote = evictableSpans.filter {
                it.key == cacheKey && overlapsRange(it.position, it.length, newRange)
            }
            for (span in toPromote) {
                if (evictableSpans.remove(span)) {
                    evictableBytes = (evictableBytes - span.length).coerceAtLeast(0L)
                    if (protectedSpans.add(span)) {
                        protectedBytes += span.length
                    }
                }
            }
        }
    }

    /**
     * Demotes already-watched trailing spans of [cacheKey] strictly behind [behindByteOffset]
     * from Tier A -> Tier B so watched portions of an episode are evicted before other shows'
     * preloaded opening windows.
     */
    fun demoteTrailingChunks(cacheKey: String, behindByteOffset: Long) {
        if (cacheKey.isBlank() || behindByteOffset <= 0L) return
        synchronized(lock) {
            val ranges = hotRangesByKey[cacheKey]
            if (ranges != null) {
                val updated = ArrayList<LongRange>(ranges.size)
                for (r in ranges) {
                    if (r.last >= behindByteOffset) {
                        updated.add(maxOf(r.first, behindByteOffset)..r.last)
                    }
                }
                if (updated.isEmpty()) {
                    hotRangesByKey.remove(cacheKey)
                } else {
                    hotRangesByKey[cacheKey] = updated
                }
            }
            val toDemote = protectedSpans.filter {
                it.key == cacheKey && (it.position + it.length) <= behindByteOffset
            }
            for (span in toDemote) {
                if (protectedSpans.remove(span)) {
                    protectedBytes = (protectedBytes - span.length).coerceAtLeast(0L)
                    if (evictableSpans.add(span)) {
                        evictableBytes += span.length
                    }
                }
            }
        }
    }

    fun unpinKey(cacheKey: String) {
        if (cacheKey.isBlank()) return
        synchronized(lock) {
            unpinKeyLocked(cacheKey)
        }
    }

    fun clearPins() {
        synchronized(lock) {
            hotRangesByKey.clear()
            for (span in protectedSpans) {
                if (evictableSpans.add(span)) {
                    evictableBytes += span.length
                }
            }
            protectedSpans.clear()
            protectedBytes = 0L
        }
    }

    private fun unpinKeyLocked(cacheKey: String) {
        hotRangesByKey.remove(cacheKey)
        val toDemote = protectedSpans.filter { it.key == cacheKey }
        for (span in toDemote) {
            if (protectedSpans.remove(span)) {
                protectedBytes = (protectedBytes - span.length).coerceAtLeast(0L)
                if (evictableSpans.add(span)) {
                    evictableBytes += span.length
                }
            }
        }
    }

    private fun isSpanProtectedLocked(key: String, position: Long, length: Long): Boolean {
        val ranges = hotRangesByKey[key] ?: return false
        for (r in ranges) {
            if (overlapsRange(position, length, r)) return true
        }
        return false
    }

    private fun overlapsRange(position: Long, length: Long, range: LongRange): Boolean {
        val spanEnd = position + length - 1L
        return position <= range.last && spanEnd >= range.first
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        var safetyIterations = 0
        while (safetyIterations < 4096) {
            safetyIterations++
            val candidate: CacheSpan = synchronized(lock) {
                val total = protectedBytes + evictableBytes
                val overTotal = total + requiredSpace > maxBytes
                val overProtectedCap = protectedBytes > protectedBudgetBytes && overTotal
                when {
                    !overTotal -> return
                    overProtectedCap && protectedSpans.isNotEmpty() -> protectedSpans.first()
                    evictableSpans.isNotEmpty() -> evictableSpans.first()
                    protectedSpans.isNotEmpty() -> protectedSpans.first()
                    else -> return
                }
            }
            val sizeBefore = totalTrackedBytes
            cache.removeSpan(candidate)
            // Guard against a locked span that SimpleCache cannot evict right now.
            if (totalTrackedBytes >= sizeBefore) {
                break
            }
        }
    }

    private fun mergeRangesInPlace(ranges: MutableList<LongRange>) {
        if (ranges.size <= 1) return
        ranges.sortBy { it.first }
        val merged = ArrayList<LongRange>(ranges.size)
        var cur = ranges[0]
        for (i in 1 until ranges.size) {
            val next = ranges[i]
            if (next.first <= cur.last + 1L) {
                cur = cur.first..maxOf(cur.last, next.last)
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        ranges.clear()
        ranges.addAll(merged)
    }

    companion object {
        private const val MAX_RANGES_PER_KEY = 6
        private const val MAX_PINNED_KEYS = 96
    }
}
