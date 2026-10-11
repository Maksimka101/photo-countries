package dev.photocountries.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

@Composable
actual fun rememberGalleryAccessRequest(onResult: (granted: Boolean) -> Unit): (() -> Unit)? = null

actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(usePlatformDefaultWidth = false)
