package com.dot.agent.router

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Deterministic local date/time parser. No AI, no network, sub-millisecond.
 * Returns null when the input is genuinely ambiguous rather than guessing.
 */
class DateTimeParser(private val zoneId: ZoneId) {

    data class Parsed(val instant: Instant, val hadExplicitDate: Boolean, val hadExplicitTime: Boolean)

    private val timeRegex = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)

    private val relativeDays = mapOf(
        "today" to 0L, "tonight" to 0L, "tomorrow" to 1L, "tmrw" to 1L,
        "yesterday" to -1L,
    )

    private val weekdayNames = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    fun parse(input: String, now: Instant): Parsed? {
        val raw = input.lowercase().trim()
        if (raw.isEmpty()) return null

        val hadDate = findDate(raw, now) != null
        val datePart = findDate(raw, now) ?: now.atZone(zoneId).toLocalDate()
        // Strip date expressions first: the year/month/day digits would otherwise be
        // misread as a clock time (e.g. "2026-10-05" yielding 20:26).
        val time = findTime(stripDates(raw))

        return when {
            // "at 8" with no day -> today, unless that time already passed -> tomorrow
            time != null -> {
                var candidate = ZonedDateTime.of(datePart, time, zoneId)
                if (!hadDate && candidate.toInstant().isBefore(now)) {
                    candidate = candidate.plusDays(1)
                }
                Parsed(candidate.toInstant(), hadDate, true)
            }
            // "tomorrow" with no time -> default to 9:00 AM local
            hadDate -> {
                val candidate = ZonedDateTime.of(datePart, LocalTime.of(9, 0), zoneId)
                Parsed(candidate.toInstant(), true, false)
            }
            else -> null
        }
    }

    private fun findDate(text: String, now: Instant): LocalDate? {
        val today = now.atZone(zoneId).toLocalDate()

        relativeDays.forEach { (word, offset) ->
            if (Regex("""\b$word\b""").containsMatchIn(text)) {
                return today.plusDays(offset)
            }
        }

        // "in 3 days" / "in 2 hours"
        Regex("""\bin (\d+) (day|days|hour|hours|week|weeks)\b""").find(text)?.let { m ->
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2].removeSuffix("s")) {
                "day" -> today.plusDays(n)
                "week" -> today.plusWeeks(n)
                "hour" -> today // hour handled by findTime
                else -> today
            }
        }

        // explicit dates: 27/09, 2026-09-27, 27-09-2026
        Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""").find(text)?.let { m ->
            return runCatching {
                LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }.getOrNull()
        }
        Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""").find(text)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let {
                if (it.length == 2) "20$it".toInt() else it.toInt()
            } ?: today.year
            return runCatching { LocalDate.of(y, mo, d) }.getOrNull()
        }

        // weekday names: "on friday" -> next occurrence
        weekdayNames.forEachIndexed { idx, name ->
            if (Regex("""\b$name\b""").containsMatchIn(text)) {
                val target = DayOfWeek.of(idx + 1)
                var days = (target.value - today.dayOfWeek.value + 7) % 7
                if (days == 0) days = 7
                return today.plusDays(days.toLong())
            }
        }

        // "next week"
        if (Regex("""\bnext week\b""").containsMatchIn(text)) return today.plusWeeks(1)

        return null
    }

    private fun findTime(text: String): LocalTime? {
        // "in 2 hours"
        Regex("""\bin (\d+) (hour|hours)\b""").find(text)?.let { m ->
            val now = LocalTime.now()
            return now.plus(m.groupValues[1].toLong(), ChronoUnit.HOURS)
        }

        val m = timeRegex.find(text) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        val meridiem = m.groupValues[3].lowercase()

        if (meridiem.isNotEmpty()) {
            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
        } else if (hour in 1..11) {
            // A bare hour with no am/pm in a productivity utterance means PM:
            // "remind me at 8" is 20:00, not 08:00. Users who mean morning say "8 am".
            hour += 12
        }
        if (hour > 23 || minute > 59) return null
        return LocalTime.of(hour, minute)
    }

    /** Removes date expressions so their digits can't be parsed as a clock time. */
    private fun stripDates(text: String): String = text
        .replace(Regex("""\b\d{4}-\d{2}-\d{2}\b"""), " ")
        .replace(Regex("""\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b"""), " ")
        .replace(
            Regex(
                """\b(today|tonight|tomorrow|tmrw|yesterday|next week|""" +
                    """monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
            ),
            " ",
        )
}
