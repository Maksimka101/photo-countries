package dev.photocountries.ui

import java.util.Locale

actual fun countryName(code: String): String = try {
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.getDefault()).ifEmpty { code }
} catch (_: IllegalArgumentException) {
    // IllformedLocaleException: код не похож на регион.
    code
}
