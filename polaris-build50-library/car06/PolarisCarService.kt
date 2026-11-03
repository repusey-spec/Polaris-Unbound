package app.hush.music.polaris

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.hush.music.BuildConfig

/** CAL API 8 UI; MediaBrowser remains available for older hosts and voice actions. */
class PolarisCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(this).build()

    override fun onCreateSession(): Session = PolarisCarSession()
}

class PolarisCarSession : Session() {
    private lateinit var client: PolarisCarClient
    override fun onCreateScreen(intent: Intent): Screen {
        client = PolarisCarClient(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { client.close() }
        })
        client.connect()
        if (intent.action == PolarisCarNavigation.SHOW_PLAYBACK) {
            return PolarisCarPlaybackScreen(carContext, client)
        }
        return PolarisCarHomeScreen(carContext, client)
    }

    override fun onNewIntent(intent: Intent) {
        if (intent.action == PolarisCarNavigation.SHOW_PLAYBACK && ::client.isInitialized) {
            val manager = carContext.getCarService(androidx.car.app.ScreenManager::class.java)
            if (manager.top !is PolarisCarPlaybackScreen) {
                manager.popToRoot()
                manager.push(PolarisCarPlaybackScreen(carContext, client))
            }
        }
    }
}
