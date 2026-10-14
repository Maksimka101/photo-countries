package dev.photocountries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.photocountries.AppState
import dev.photocountries.GalleryStatus
import dev.photocountries.LoadError
import dev.photocountries.LoadStatus
import dev.photocountries.shared.*
import dev.photocountries.yandex.authorizeUrl
import org.jetbrains.compose.resources.stringResource

/**
 * Источники фото: Яндекс Диск и галерея телефона. Нужен хотя бы один, второй можно подключить позже —
 * этот же экран открывается из заголовка. Закрывает его «Готово»; пока не подключено ничего, уйти нельзя ([onDone] null).
 */
@Composable
fun SourcesScreen(
    state: AppState,
    onToken: (String) -> Unit,
    onDiskOff: () -> Unit,
    onGalleryOn: () -> Unit,
    onGalleryOff: () -> Unit,
    onDone: (() -> Unit)?,
) {
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.widthIn(max = 480.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(Modifier.padding(top = 24.dp).size(64.dp), color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Icon(AppIcons.Globe, null, Modifier.padding(16.dp))
            }
            Text(stringResource(Res.string.sources_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(Res.string.sources_about),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DiskCard(state.hasToken, onToken, onDiskOff)
            GalleryCard(state, onGalleryOn, onGalleryOff)
            if (onDone != null) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.sources_done)) }
            }
        }
    }
}

@Composable
private fun SourceCard(title: String, about: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(about, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun Connected(onOff: () -> Unit) {
    Text(stringResource(Res.string.source_connected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    OutlinedButton(onClick = onOff, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.source_disconnect)) }
}

@Composable
private fun Warning(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
        Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Диск: кнопка открывает страницу Яндекса в браузере, токен возвращается в приложение адресом
 * `photocountries://auth#access_token=...`. Запасной путь — вставить токен руками; пока OAuth-клиент
 * не зарегистрирован, он единственный, и экран прямо об этом говорит.
 */
@Composable
private fun DiskCard(connected: Boolean, onToken: (String) -> Unit, onOff: () -> Unit) {
    SourceCard(stringResource(Res.string.disk_title), stringResource(Res.string.login_about)) {
        if (connected) {
            Connected(onOff)
            return@SourceCard
        }
        val uriHandler = LocalUriHandler.current
        val url = remember { authorizeUrl() }
        // Токен не сохраняем через rememberSaveable: ему нечего делать в сохранённом состоянии Activity.
        var pasted by remember { mutableStateOf("") }
        Button(onClick = { url?.let(uriHandler::openUri) }, enabled = url != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.login_browser))
        }
        if (url == null) Warning(stringResource(Res.string.login_no_client))
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text(stringResource(Res.string.login_token_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = { onToken(pasted) }, enabled = pasted.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.login_token_save))
        }
    }
}

/** Галерея: подключение — это разрешение системы на фото и их геоданные. Там, где галереи нет, карточки нет. */
@Composable
private fun GalleryCard(state: AppState, onOn: () -> Unit, onOff: () -> Unit) {
    var denied by remember { mutableStateOf(false) }
    val request = rememberGalleryAccessRequest { granted ->
        denied = !granted
        if (granted) onOn()
    } ?: return
    SourceCard(stringResource(Res.string.gallery_title), stringResource(Res.string.gallery_about)) {
        // Разрешение отозвали уже после подключения — даём вернуть его, не теряя прочитанное.
        val on = state.gallery == GalleryStatus.On
        val lost = on && state.load == LoadStatus.Error(LoadError.GalleryDenied)
        if (denied || lost) Warning(stringResource(Res.string.gallery_denied))
        if (on) {
            if (lost) Button(onClick = request, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.gallery_allow)) }
            Connected(onOff)
        } else {
            Button(onClick = request, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.gallery_connect)) }
        }
    }
}
