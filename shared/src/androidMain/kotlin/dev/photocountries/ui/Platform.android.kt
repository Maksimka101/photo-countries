package dev.photocountries.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import dev.photocountries.gallery.GALLERY_PERMISSIONS
import dev.photocountries.gallery.hasGalleryAccess

@Composable
actual fun rememberGalleryAccessRequest(onResult: (granted: Boolean) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // Ответ системы по каждому разрешению не разбираем: итог один — можно читать галерею или нет.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(hasGalleryAccess(context))
    }
    return { launcher.launch(GALLERY_PERMISSIONS) }
}

actual fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
