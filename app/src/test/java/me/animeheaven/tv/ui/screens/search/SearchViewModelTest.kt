package me.animeheaven.tv.ui.screens.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.animeheaven.tv.data.model.AnimeCard
import me.animeheaven.tv.data.model.FastSearchItem
import me.animeheaven.tv.data.model.KeyboardMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeSearchGateway : SearchGateway {
        val recentFlow = MutableStateFlow(listOf("dandadan", "clover"))
        val fastQueries = mutableListOf<String>()
        val fullQueries = mutableListOf<String>()
        val recorded = mutableListOf<String>()

        override fun observeRecentSearches(): Flow<List<String>> = recentFlow

        override suspend fun fastSearch(query: String): List<FastSearchItem> {
            fastQueries.add(query)
            return listOf(
                FastSearchItem("0pqfl", "Black Clover 2nd Season", "https://animeheaven.me/image.php?82gob")
            )
        }

        override suspend fun fullSearch(query: String): Pair<String, List<AnimeCard>> {
            fullQueries.add(query)
            return query to listOf(
                AnimeCard(id = "l1cen", title = "Black Clover", coverUrl = "https://animeheaven.me/image.php?mg1oq")
            )
        }

        override suspend fun recordSearch(query: String) {
            recorded.add(query)
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `defaults to QWERTY and handles typing, backspace, space, mode switch and debounced fastsearch`() = runTest {
        val gateway = FakeSearchGateway()
        val vm = SearchViewModel(gateway = gateway, debounceMillis = 250L)

        assertEquals(KeyboardMode.QWERTY, vm.uiState.value.keyboardMode)

        vm.appendChar("b")
        vm.appendChar("l")
        vm.appendChar("a")
        vm.appendSpace()
        vm.appendChar("x")
        vm.backspace()
        vm.appendChar("c")
        assertEquals("bla c", vm.uiState.value.query)

        // Debounced live search should trigger after 250ms
        advanceTimeBy(300L)
        advanceUntilIdle()

        assertEquals(listOf("bla c"), gateway.fastQueries)
        assertEquals(1, vm.uiState.value.fastResults.size)
        assertFalse(vm.uiState.value.isFullSearchMode)

        // Switch keyboard modes
        vm.setKeyboardMode(KeyboardMode.ABC)
        assertEquals(KeyboardMode.ABC, vm.uiState.value.keyboardMode)
        vm.setKeyboardMode(KeyboardMode.SYMBOLS)
        assertEquals(KeyboardMode.SYMBOLS, vm.uiState.value.keyboardMode)

        // Submit full search
        vm.submitFullSearch()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isFullSearchMode)
        assertEquals(listOf("bla c"), gateway.fullQueries)
        assertEquals(listOf("bla c"), gateway.recorded)
        assertEquals(1, vm.uiState.value.fullResults.size)

        // Clear resets query and results
        vm.clearQuery()
        advanceUntilIdle()
        assertEquals("", vm.uiState.value.query)
        assertTrue(vm.uiState.value.fastResults.isEmpty())
        assertTrue(vm.uiState.value.fullResults.isEmpty())
    }

    @Test
    fun `full submission and recent selection invalidate pending typing`() = runTest {
        val gateway = FakeSearchGateway()
        val vm = SearchViewModel(gateway = gateway, debounceMillis = 250L)
        vm.appendChar("clover")
        advanceTimeBy(125)
        vm.submitFullSearch()
        advanceUntilIdle()
        vm.applyRecentQuery("dandadan")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), gateway.fastQueries)
        assertEquals(listOf("clover", "dandadan"), gateway.fullQueries)
        assertTrue(vm.uiState.value.isFullSearchMode)
    }
}
