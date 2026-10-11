package dev.photocountries.ui

import kotlinx.datetime.LocalDate

/** Русская форма слова при числе: 1 день, 2 дня, 5 дней. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val d = n % 10
    val h = n % 100
    return when {
        d == 1 && h != 11 -> one
        d in 2..4 && h !in 12..14 -> few
        else -> many
    }
}

fun daysText(n: Int) = "$n ${plural(n, "день", "дня", "дней")}"
fun visitsText(n: Int) = "$n ${plural(n, "визит", "визита", "визитов")}"
fun countriesWord(n: Int) = plural(n, "страна", "страны", "стран")
fun yearsWord(n: Int) = plural(n, "год", "года", "лет")

private fun two(n: Int) = n.toString().padStart(2, '0')

/** 05.03.2024 */
fun formatDate(d: LocalDate) = "${two(d.day)}.${two(d.month.ordinal + 1)}.${d.year}"

/** 05.03 */
fun formatDayMonth(d: LocalDate) = "${two(d.day)}.${two(d.month.ordinal + 1)}"

/** Диапазон дат без повторов: один день, «05.03 – 12.04.2024» или две полные даты через год. */
fun formatDateRange(a: LocalDate, b: LocalDate): String = when {
    a == b -> formatDate(a)
    a.year == b.year -> "${formatDayMonth(a)} – ${formatDate(b)}"
    else -> "${formatDate(a)} – ${formatDate(b)}"
}
