package dev.photocountries.yandex

/**
 * ID OAuth-клиента Яндекса. Не секрет: клиент публичный (implicit flow без client secret), ID виден
 * в адресе входа и в любом собранном APK. С пустой строкой вход через браузер выключен и работает
 * только вставка токена.
 *
 * Свой клиент: https://oauth.yandex.ru/client/new — платформа «веб-сервисы», Redirect URI [REDIRECT_URI],
 * право `cloud_api:disk.read`.
 */
const val YANDEX_CLIENT_ID = "f748d4ae5a0f4cb7bdb4438be1dc1b7a"

/** Куда Яндекс возвращает после входа; эту схему ловит intent-filter у Activity. */
const val REDIRECT_URI = "photocountries://auth"

/**
 * Адрес входа (implicit flow, без бэкенда): токен вернётся во фрагменте адреса [REDIRECT_URI].
 * null — клиент ещё не зарегистрирован.
 */
fun authorizeUrl(clientId: String = YANDEX_CLIENT_ID): String? =
    if (clientId.isBlank()) null else "https://oauth.yandex.ru/authorize?response_type=token&client_id=$clientId"

/**
 * Достаёт токен из адреса возврата `photocountries://auth#access_token=...&token_type=bearer&expires_in=...`.
 * null — адрес чужой, во фрагменте нет токена или там ошибка (`#error=access_denied`).
 */
fun tokenFromRedirect(uri: String): String? {
    if (!uri.startsWith(REDIRECT_URI)) return null
    val fragment = uri.substringAfter('#', "")
    return fragment.split('&')
        .firstOrNull { it.startsWith("access_token=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }
}
