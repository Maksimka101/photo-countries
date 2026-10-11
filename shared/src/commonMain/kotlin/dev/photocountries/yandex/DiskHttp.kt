package dev.photocountries.yandex

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readRemaining
import kotlinx.io.IOException
import kotlinx.io.readString

private const val TIMEOUT_MS = 20_000L
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024

/** Диск не принял токен (HTTP 401/403): нужен новый вход. */
class UnauthorizedException : IOException("Я.Диск не принял токен")

// Движок — тот, что подключён зависимостью платформы.
private val client by lazy {
    HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = TIMEOUT_MS
            socketTimeoutMillis = 3 * TIMEOUT_MS
            requestTimeoutMillis = 6 * TIMEOUT_MS
        }
    }
}

/** GET к API диска с заголовком `Authorization: OAuth <token>`. Не 200 и ответ больше 16 МБ — ошибка. */
suspend fun diskGet(url: String, token: String): String =
    client.prepareGet(url) {
        accept(ContentType.Application.Json)
        header(HttpHeaders.Authorization, "OAuth $token")
    }.execute { response ->
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> throw UnauthorizedException()
            else -> throw IOException("HTTP ${response.status.value} от Я.Диска")
        }
        val body = response.bodyAsChannel().readRemaining(MAX_RESPONSE_BYTES + 1)
        if (body.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Ответ Я.Диска больше $MAX_RESPONSE_BYTES байт")
        body.readString()
    }
