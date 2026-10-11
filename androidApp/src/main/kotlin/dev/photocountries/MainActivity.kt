package dev.photocountries

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private val model get() = (application as PhotoCountriesApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // С тремя кнопками система иначе кладёт под них свою подложку, и приложение обрывается о неё.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        // Только при первом создании: после поворота экрана в intent лежит тот же адрес возврата.
        if (savedInstanceState == null) handleAuthRedirect(intent)
        setContent {
            App(model)
        }
    }

    override fun onStart() {
        super.onStart()
        // Новые фото подтягиваются при каждом возврате в приложение; до чтения токена это ничего не делает.
        model.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAuthRedirect(intent)
    }

    /** Возврат из браузера после входа: токен во фрагменте адреса `photocountries://auth#access_token=...`. */
    private fun handleAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (model.handleRedirect(uri.toString())) {
            // Токен забрали — убираем его из intent, чтобы не остался в задаче.
            intent.data = null
        }
    }
}
