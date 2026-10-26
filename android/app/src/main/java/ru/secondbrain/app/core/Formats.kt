package ru.secondbrain.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

private val RU = Locale.forLanguageTag("ru-RU")
private val monthsGen = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
    "сентября", "октября", "ноября", "декабря")
private val weekdays = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
private val weekdaysShort = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")

/** 123456 kopecks → "1 234 ₽" (or "1 234,56 ₽"). */
fun rub(minor: Long?, kopecks: Boolean = false): String {
    if (minor == null) return "—"
    val sign = if (minor < 0) "−" else ""
    val v = abs(minor)
    val rub = v / 100
    val kop = v % 100
    val grouped = rub.toString().reversed().chunked(3).joinToString(" ").reversed()
    return if (kopecks && kop != 0L) "$sign$grouped,${kop.toString().padStart(2, '0')} ₽" else "$sign$grouped ₽"
}

fun parseRubToMinor(text: String): Long? {
    val clean = text.replace(" ", "").replace(" ", "").replace(" ", "").replace(",", ".").replace("₽", "")
    val value = clean.toBigDecimalOrNull() ?: return null
    return value.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
}

fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
}

fun parseDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
        ?: parseInstant(value)?.atZone(ZoneId.systemDefault())?.toLocalDate()
}

fun nowIso(): String = OffsetDateTime.now().toString()

fun Instant.local(): ZonedDateTime = atZone(ZoneId.systemDefault())

fun ruDate(d: LocalDate, withYear: Boolean = d.year != LocalDate.now().year): String =
    "${d.dayOfMonth} ${monthsGen[d.monthValue - 1]}" + if (withYear) " ${d.year}" else ""

fun ruWeekday(d: LocalDate) = weekdays[d.dayOfWeek.value - 1]
fun ruWeekdayShort(isoDay: Int) = weekdaysShort[(isoDay - 1).coerceIn(0, 6)]
fun ruWeekdayName(isoDay: Int) = weekdays[(isoDay - 1).coerceIn(0, 6)]

fun time(i: Instant): String = i.local().format(DateTimeFormatter.ofPattern("HH:mm"))

/** "сегодня 14:05", "вчера 09:12", "3 октября 18:00". */
/** "сегодня 14:14", "вчера 9:05", "19 октября 21:00"; [withTime] = false for date-only values (statement rows). */
fun relative(i: Instant?, withTime: Boolean = true): String {
    if (i == null) return ""
    val z = i.local()
    val today = LocalDate.now()
    val t = if (withTime) " ${time(i)}" else ""
    return when (val d = z.toLocalDate()) {
        today -> "сегодня$t"
        today.minusDays(1) -> "вчера$t"
        else -> "${ruDate(d)}$t"
    }
}

fun daysWord(n: Long): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1L && m100 != 11L -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

fun daysUntil(d: LocalDate): Long = ChronoUnit.DAYS.between(LocalDate.now(), d)

fun decimal(v: Double?, digits: Int = 1): String =
    if (v == null) "—" else if (v % 1.0 == 0.0) v.toLong().toString() else String.format(RU, "%.${digits}f", v)
