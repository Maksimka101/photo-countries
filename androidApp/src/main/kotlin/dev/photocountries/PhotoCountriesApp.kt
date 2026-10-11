package dev.photocountries

import android.app.Application
import dev.photocountries.data.FileStorage
import dev.photocountries.gallery.AndroidGallery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Держит то, что живёт дольше Activity: модель одна на процесс, загрузка не обрывается при повороте
 * экрана. Токен и кэш точек лежат в приватном `filesDir`.
 */
class PhotoCountriesApp : Application() {
    val model: AppModel by lazy {
        // Один поток на всю модель: её поля меняются только из её же корутин.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        AppModel(FileStorage(filesDir), scope, gallery = AndroidGallery(this)).also { it.start() }
    }
}
