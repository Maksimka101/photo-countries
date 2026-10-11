package dev.photocountries.core

/** Глубже при пропуске значений не идём: защита от переполнения стека на мусоре. */
private const val MAX_DEPTH = 64

/**
 * Минимальный потоковый читатель JSON: ровно столько, сколько нужно, чтобы достать несколько полей
 * и пропустить остальное, не строя дерево (ответы диска бывают на мегабайты).
 *
 * Обход объекта: [beginObject], затем пока [nextName] не вернёт null — прочитать или [skipValue] значение.
 * Обход массива: [beginArray], затем пока [nextElement] — прочитать элемент.
 * Ошибки формата — IllegalArgumentException.
 */
class JsonReader(private val s: String) {
    private var pos = 0

    fun fail(message: String): Nothing =
        throw IllegalArgumentException("Некорректный JSON: $message (позиция $pos)")

    private fun skipWs() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
    }

    private fun consumeIf(c: Char): Boolean {
        if (pos < s.length && s[pos] == c) { pos++; return true }
        return false
    }

    private fun expect(c: Char) {
        if (!consumeIf(c)) fail(if (pos >= s.length) "ожидался '$c', конец данных" else "ожидался '$c', найден '${s[pos]}'")
    }

    private fun peek(): Char {
        skipWs()
        if (pos >= s.length) fail("ожидалось значение, конец данных")
        return s[pos]
    }

    fun beginObject() { skipWs(); expect('{') }

    /** Имя следующего поля или null, если объект закончился. */
    fun nextName(): String? {
        skipWs()
        if (consumeIf('}')) return null
        if (consumeIf(',')) skipWs()
        val name = readRawString()
        skipWs()
        expect(':')
        return name
    }

    fun beginArray() { skipWs(); expect('[') }

    /** Есть ли следующий элемент; на конце массива съедает `]`. */
    fun nextElement(): Boolean {
        skipWs()
        if (consumeIf(']')) return false
        consumeIf(',')
        return true
    }

    /** true, если дальше объект; иначе значение пропускается (null, строка и т. п.). */
    fun beginObjectOrSkip(): Boolean {
        if (peek() == '{') { pos++; return true }
        skipValue()
        return false
    }

    /** Строка или null, если значение другого типа (оно пропускается). */
    fun readStringOrNull(): String? {
        if (peek() == '"') return readRawString()
        skipValue()
        return null
    }

    /** Число или null, если значение другого типа (оно пропускается). */
    fun readDoubleOrNull(): Double? {
        val c = peek()
        if (c == '-' || c in '0'..'9') return readNumberToken().toDoubleOrNull()
        skipValue()
        return null
    }

    fun skipValue() = skipValue(0)

    private fun readRawString(): String {
        expect('"')
        val start = pos
        // Быстрый путь: строка без escape — просто кусок исходного текста.
        while (pos < s.length && s[pos] != '"' && s[pos] != '\\') pos++
        if (pos < s.length && s[pos] == '"') return s.substring(start, pos++)
        val sb = StringBuilder(s.substring(start, pos))
        while (true) {
            if (pos >= s.length) fail("не закрыта строка")
            when (val c = s[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= s.length) fail("оборвана escape-последовательность")
                    when (val e = s[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > s.length) fail("оборвана escape-последовательность \\u")
                            val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("неверный \\u-код")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> fail("неизвестный escape '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readNumberToken(): String {
        val start = pos
        consumeIf('-')
        if (digits() == 0) fail("ожидалась цифра")
        if (consumeIf('.') && digits() == 0) fail("нет цифр после точки")
        if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
            pos++
            if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
            if (digits() == 0) fail("нет цифр в экспоненте")
        }
        return s.substring(start, pos)
    }

    private fun digits(): Int {
        val start = pos
        while (pos < s.length && s[pos] in '0'..'9') pos++
        return pos - start
    }

    private fun skipValue(depth: Int) {
        if (depth > MAX_DEPTH) fail("слишком глубокая вложенность")
        when (val c = peek()) {
            '"' -> readRawString()
            '{' -> {
                pos++
                while (nextName() != null) skipValue(depth + 1)
            }
            '[' -> {
                pos++
                while (nextElement()) skipValue(depth + 1)
            }
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> if (c == '-' || c in '0'..'9') readNumberToken() else fail("неожиданный символ '$c'")
        }
    }

    private fun literal(word: String) {
        if (!s.startsWith(word, pos)) fail("ожидалось $word")
        pos += word.length
    }
}
