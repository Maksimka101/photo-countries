package dev.photocountries

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.photocountries.data.FileStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

// На desktop адрес возврата из браузера поймать нечем — вход только вставкой токена.
fun main() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val model = AppModel(FileStorage(dataDir()), scope)
    model.start()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Где я был",
            state = rememberWindowState(width = 420.dp, height = 760.dp),
        ) {
            App(model)
        }
    }
}

/** Каталог данных приложения по правилам ОС. */
private fun dataDir(): File {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        "win" in os -> System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
        "mac" in os -> "$home/Library/Application Support"
        else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
    }
    return File(base, "photo-countries")
}
