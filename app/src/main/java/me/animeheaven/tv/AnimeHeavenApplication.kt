package me.animeheaven.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class AnimeHeavenApplication : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.warmUpOnStartup()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) {
            container.onTrimMemory(level)
        }
    }
}
