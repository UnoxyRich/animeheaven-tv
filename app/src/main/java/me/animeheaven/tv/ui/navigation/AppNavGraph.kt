package me.animeheaven.tv.ui.navigation

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import me.animeheaven.tv.AppContainer
import me.animeheaven.tv.ui.components.AhLoadingBox
import me.animeheaven.tv.ui.screens.bookmarks.BookmarksScreen
import me.animeheaven.tv.ui.screens.bookmarks.BookmarksViewModel
import me.animeheaven.tv.ui.screens.catalog.CatalogKind
import me.animeheaven.tv.ui.screens.catalog.CatalogScreen
import me.animeheaven.tv.ui.screens.catalog.CatalogViewModel
import me.animeheaven.tv.ui.screens.detail.AnimeDetailScreen
import me.animeheaven.tv.ui.screens.detail.DetailViewModel
import me.animeheaven.tv.ui.screens.home.HomeScreen
import me.animeheaven.tv.ui.screens.home.HomeViewModel
import me.animeheaven.tv.ui.screens.player.PlayerScreen
import me.animeheaven.tv.ui.screens.player.PlayerViewModel
import me.animeheaven.tv.ui.screens.search.RepositorySearchGateway
import me.animeheaven.tv.ui.screens.search.SearchScreen
import me.animeheaven.tv.ui.screens.search.SearchViewModel
import me.animeheaven.tv.ui.screens.settings.SettingsScreen

private object Routes {
    const val HOME = "home"
    const val NEW = "new"
    const val POPULAR = "popular"
    const val BOOKMARKS = "bookmarks"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val SEASONAL = "seasonal/{path}/{label}"
    const val TAG = "tag/{tag}"
    const val DETAIL = "detail/{animeId}"
    const val PLAYER = "player/{episodeKey}/{animeId}/{animeTitle}/{coverUrl}"

    fun seasonal(path: String, label: String) =
        "seasonal/${Uri.encode(path)}/${Uri.encode(label)}"

    fun tag(tag: String) = "tag/${Uri.encode(tag)}"

    fun detail(animeId: String) = "detail/${Uri.encode(animeId)}"

    fun player(
        episodeKey: String,
        animeId: String,
        animeTitle: String,
        coverUrl: String
    ): String = "player/${Uri.encode(episodeKey)}/${Uri.encode(animeId.ifBlank { "_" })}/" +
        "${Uri.encode(animeTitle.ifBlank { "AnimeHeaven" })}/${Uri.encode(coverUrl.ifBlank { "_" })}"
}

@Composable
fun AppNavGraph(container: AppContainer) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var resolvingRandom by remember { mutableStateOf(false) }

    val navigateTopTab: (String) -> Unit = remember(navController) {
        { route ->
            navController.navigate(route) {
                popUpTo(Routes.HOME) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val openRandomAnime: () -> Unit = remember(navController, container) {
        {
            if (!resolvingRandom) {
                resolvingRandom = true
                scope.launch {
                    runCatching { container.repository.getRandomAnimeId() }
                        .onSuccess { randomId ->
                            resolvingRandom = false
                            navController.navigate(Routes.detail(randomId))
                        }
                        .onFailure {
                            resolvingRandom = false
                        }
                }
            }
        }
    }

    val openAnime: (String) -> Unit = remember(navController) {
        { id -> navController.navigate(Routes.detail(id)) }
    }
    val openHome: () -> Unit = remember(navigateTopTab) { { navigateTopTab(Routes.HOME) } }
    val openSearch: () -> Unit = remember(navController) { { navController.navigate(Routes.SEARCH) } }
    val openNew: () -> Unit = remember(navigateTopTab) { { navigateTopTab(Routes.NEW) } }
    val openBookmarks: () -> Unit = remember(navigateTopTab) { { navigateTopTab(Routes.BOOKMARKS) } }
    val openPopular: () -> Unit = remember(navigateTopTab) { { navigateTopTab(Routes.POPULAR) } }
    val openSeasonal: (String, String) -> Unit = remember(navigateTopTab) {
        { path, label -> navigateTopTab(Routes.seasonal(path, label)) }
    }
    val openSettings: () -> Unit = remember(navController) { { navController.navigate(Routes.SETTINGS) } }
    val popBack: () -> Unit = remember(navController) { { navController.popBackStack() } }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            enterTransition = { fadeIn(animationSpec = tween(130)) },
            exitTransition = { fadeOut(animationSpec = tween(100)) },
            popEnterTransition = { fadeIn(animationSpec = tween(130)) },
            popExitTransition = { fadeOut(animationSpec = tween(100)) }
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(
                    factory = simpleFactory {
                        HomeViewModel(
                            container.repository,
                            container.settingsStore,
                            container.preloader
                        )
                    }
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onPlayContinueItem = { cw ->
                        navController.navigate(
                            Routes.player(
                                episodeKey = cw.targetEpisodeKey,
                                animeId = cw.animeId,
                                animeTitle = cw.animeTitle,
                                coverUrl = cw.coverUrl
                            )
                        )
                    },
                    onOpenSearch = openSearch,
                    onOpenNew = openNew,
                    onOpenBookmarks = openBookmarks,
                    onOpenPopular = openPopular,
                    onOpenSeasonal = openSeasonal,
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(Routes.NEW) {
                val vm: CatalogViewModel = viewModel(
                    factory = simpleFactory {
                        CatalogViewModel(
                            container.repository,
                            container.settingsStore,
                            CatalogKind.NEW,
                            "new.php",
                            "New Episodes",
                            container.preloader
                        )
                    }
                )
                CatalogScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onOpenHome = openHome,
                    onOpenSearch = openSearch,
                    onOpenNew = {},
                    onOpenBookmarks = openBookmarks,
                    onOpenPopular = openPopular,
                    onOpenSeasonal = openSeasonal,
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(Routes.POPULAR) {
                val vm: CatalogViewModel = viewModel(
                    factory = simpleFactory {
                        CatalogViewModel(
                            container.repository,
                            container.settingsStore,
                            CatalogKind.POPULAR,
                            "popular.php",
                            "Popular Today",
                            container.preloader
                        )
                    }
                )
                CatalogScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onOpenHome = openHome,
                    onOpenSearch = openSearch,
                    onOpenNew = openNew,
                    onOpenBookmarks = openBookmarks,
                    onOpenPopular = {},
                    onOpenSeasonal = openSeasonal,
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(
                route = Routes.SEASONAL,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("label") { type = NavType.StringType }
                )
            ) { entry ->
                val path = Uri.decode(entry.arguments?.getString("path").orEmpty())
                val label = Uri.decode(entry.arguments?.getString("label").orEmpty())
                val vm: CatalogViewModel = viewModel(
                    factory = simpleFactory {
                        CatalogViewModel(
                            container.repository,
                            container.settingsStore,
                            CatalogKind.SEASONAL,
                            path,
                            "$label Anime",
                            container.preloader
                        )
                    }
                )
                CatalogScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onOpenHome = openHome,
                    onOpenSearch = openSearch,
                    onOpenNew = openNew,
                    onOpenBookmarks = openBookmarks,
                    onOpenPopular = openPopular,
                    onOpenSeasonal = { _, _ -> },
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(
                route = Routes.TAG,
                arguments = listOf(navArgument("tag") { type = NavType.StringType })
            ) { entry ->
                val tag = Uri.decode(entry.arguments?.getString("tag").orEmpty())
                val vm: CatalogViewModel = viewModel(
                    factory = simpleFactory {
                        CatalogViewModel(
                            container.repository,
                            container.settingsStore,
                            CatalogKind.TAG,
                            tag,
                            "$tag Anime",
                            container.preloader
                        )
                    }
                )
                CatalogScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onOpenHome = openHome,
                    onOpenSearch = openSearch,
                    onOpenNew = openNew,
                    onOpenBookmarks = openBookmarks,
                    onOpenPopular = openPopular,
                    onOpenSeasonal = openSeasonal,
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(Routes.BOOKMARKS) {
                val vm: BookmarksViewModel = viewModel(
                    factory = simpleFactory {
                        BookmarksViewModel(container.repository, container.preloader)
                    }
                )
                BookmarksScreen(
                    viewModel = vm,
                    onOpenAnime = openAnime,
                    onPlayContinueItem = { cw ->
                        navController.navigate(
                            Routes.player(
                                episodeKey = cw.targetEpisodeKey,
                                animeId = cw.animeId,
                                animeTitle = cw.animeTitle,
                                coverUrl = cw.coverUrl
                            )
                        )
                    },
                    onOpenHome = openHome,
                    onOpenSearch = openSearch,
                    onOpenNew = openNew,
                    onOpenPopular = openPopular,
                    onOpenSeasonal = openSeasonal,
                    onOpenRandom = openRandomAnime,
                    onOpenSettings = openSettings
                )
            }

            composable(Routes.SEARCH) {
                val vm: SearchViewModel = viewModel(
                    factory = simpleFactory {
                        SearchViewModel(
                            gateway = RepositorySearchGateway(container.repository),
                            initialKeyboardMode = container.settingsStore.settings.value.defaultKeyboardMode,
                            preloader = container.preloader
                        )
                    }
                )
                SearchScreen(
                    viewModel = vm,
                    onBack = popBack,
                    onOpenAnime = openAnime
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("animeId") { type = NavType.StringType })
            ) { entry ->
                val animeId = Uri.decode(entry.arguments?.getString("animeId").orEmpty())
                val vm: DetailViewModel = viewModel(
                    factory = simpleFactory {
                        DetailViewModel(container.repository, animeId, container.preloader)
                    }
                )
                AnimeDetailScreen(
                    viewModel = vm,
                    onBack = popBack,
                    onPlayEpisode = { key, id, title, cover ->
                        navController.navigate(Routes.player(key, id, title, cover))
                    },
                    onOpenTag = { tag -> navController.navigate(Routes.tag(tag)) },
                    onOpenAnime = openAnime
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("episodeKey") { type = NavType.StringType },
                    navArgument("animeId") { type = NavType.StringType },
                    navArgument("animeTitle") { type = NavType.StringType },
                    navArgument("coverUrl") { type = NavType.StringType }
                )
            ) { entry ->
                val epKey = Uri.decode(entry.arguments?.getString("episodeKey").orEmpty())
                val animeId = Uri.decode(entry.arguments?.getString("animeId").orEmpty())
                    .let { if (it == "_") "" else it }
                val animeTitle = Uri.decode(entry.arguments?.getString("animeTitle").orEmpty())
                val coverUrl = Uri.decode(entry.arguments?.getString("coverUrl").orEmpty())
                    .let { if (it == "_") "" else it }

                val vm: PlayerViewModel = viewModel(
                    factory = simpleFactory {
                        PlayerViewModel(
                            repository = container.repository,
                            settingsStore = container.settingsStore,
                            skipTimesClient = container.skipTimesClient,
                            initialEpisodeKey = epKey,
                            initialAnimeId = animeId,
                            initialAnimeTitle = animeTitle,
                            initialCoverUrl = coverUrl,
                            videoCacheManager = container.videoCacheManager,
                            preloader = container.preloader
                        )
                    }
                )
                PlayerScreen(
                    viewModel = vm,
                    videoCacheManager = container.videoCacheManager,
                    onBack = popBack
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settingsStore = container.settingsStore,
                    repository = container.repository,
                    onGetCacheSummary = { container.getCacheSummaryText() },
                    onClearAllCaches = { container.clearAllCaches() },
                    onBack = popBack
                )
            }
        }

        if (resolvingRandom) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA000000))
            ) {
                AhLoadingBox(message = "Picking a random anime...")
            }
        }
    }
}

private inline fun <reified VM : ViewModel> simpleFactory(
    crossinline creator: () -> VM
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
}
