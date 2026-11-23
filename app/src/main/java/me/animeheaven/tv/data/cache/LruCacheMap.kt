package me.animeheaven.tv.data.cache

/**
 * Lightweight, pure-Kotlin thread-safe bounded LRU map so long-running TV sessions never leak
 * unbounded heap memory across repositories, probes, and skip-times caches.
 */
class LruCacheMap<K, V>(private val maxEntries: Int) {
    private val map = object : LinkedHashMap<K, V>(
        minOf(maxEntries, 32).coerceAtLeast(8),
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    operator fun set(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun containsKey(key: K): Boolean = map.containsKey(key)

    @Synchronized
    fun remove(key: K): V? = map.remove(key)

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun trimToSize(targetSize: Int) {
        val cap = targetSize.coerceAtLeast(0)
        if (map.size <= cap) return
        val iter = map.entries.iterator()
        while (map.size > cap && iter.hasNext()) {
            iter.next()
            iter.remove()
        }
    }
}
