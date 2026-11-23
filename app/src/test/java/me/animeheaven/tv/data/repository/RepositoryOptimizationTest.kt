package me.animeheaven.tv.data.repository

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RepositoryOptimizationTest {

    @Test
    fun `requestCoalescer deduplicates concurrent fetches and survives caller cancellation`() = runBlocking {
        val sharedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coalescer = RequestCoalescer<String>(sharedScope)
        val fetchCount = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()

        val callerA = async {
            coalescer.getOrFetch("detail:abc") {
                fetchCount.incrementAndGet()
                gate.await()
                "fresh-html"
            }
        }
        val callerB = async {
            coalescer.getOrFetch("detail:abc") {
                fetchCount.incrementAndGet()
                gate.await()
                "fresh-html"
            }
        }

        // Wait until the single shared fetch has started
        while (fetchCount.get() == 0) {
            kotlinx.coroutines.yield()
        }

        // Cancel caller A (simulating a cancelled D-pad focus preload); caller B must still succeed!
        callerA.cancelAndJoin()
        gate.complete(Unit)

        assertEquals("fresh-html", callerB.await())
        assertEquals(1, fetchCount.get())

        // Subsequent call after completion executes freshly
        val secondResult = coalescer.getOrFetch("detail:abc") {
            fetchCount.incrementAndGet()
            "second-html"
        }
        assertEquals("second-html", secondResult)
        assertEquals(2, fetchCount.get())
    }
}
