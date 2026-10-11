package dev.photocountries.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.photocountries.gallery.GALLERY_PERMISSIONS
import dev.photocountries.gallery.hasGalleryAccess

@Composable
actual fun platformColorScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

@Composable
actual fun rememberGalleryAccessRequest(onResult: (granted: Boolean) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    // Ответ системы по каждому разрешению не разбираем: итог один — можно читать галерею или нет.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult(hasGalleryAccess(context))
    }
    return { launcher.launch(GALLERY_PERMISSIONS) }
}
