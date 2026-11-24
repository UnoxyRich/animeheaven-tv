package me.animeheaven.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.animeheaven.tv.ui.navigation.AppNavGraph
import me.animeheaven.tv.ui.components.LocalArtworkResolver
import me.animeheaven.tv.ui.theme.AnimeHeavenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AnimeHeavenApplication).container

        setContent {
            // Observe ONLY themeId so changing non-theme settings never recomposes the root tree
            val themeId by container.settingsStore.themeId.collectAsStateWithLifecycle()
            AnimeHeavenTheme(themeId = themeId) {
                CompositionLocalProvider(LocalArtworkResolver provides container.artworkResolver) {
                    AppNavGraph(container = container)
                }
            }
        }

        // AnimeHeavenTheme paints its own full-screen background; removing the window drawable
        // eliminates 1 full-screen 1080p/4K GPU overdraw pass every frame on Android TV.
        window.setBackgroundDrawable(null)
    }
}
