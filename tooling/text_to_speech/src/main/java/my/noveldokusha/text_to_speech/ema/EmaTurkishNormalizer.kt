package my.noveldokusha.text_to_speech.ema

import java.util.Locale

/**
 * Offline spoken-Turkish frontend for the direct EMA PCM path only.
 *
 * A deliberately small, deterministic subset of normalizer-tr's fallback
 * behaviour. Never rewrite the visible novel or Android system TTS output.
 * An unrecognized word is kept as written, not silently omitted.
 *
 * The ordering is significant: dates, clocks, amounts and decimals must be
 * consumed before generic integers. No floating-point number conversion.
 */
internal object EmaTurkishNormalizer {
    private val tr = Locale.forLanguageTag("tr-TR")
    private val ones = arrayOf(
        "sıfır", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz"
    )
    private val tens = arrayOf(
        "", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan"
    )
    private val groups = arrayOf("", "bin", "milyon", "milyar")
    private val months = arrayOf(
        "", "ocak", "şubat", "mart", "nisan", "mayıs", "haziran",
        "temmuz", "ağustos", "eylül", "ekim", "kasım", "aralık"
    )
    private val dates = Regex("""(?<![\p{L}\d])(\d{1,2})[./](\d{1,2})[./](\d{4})(?!\d)""")
    private val clock = Regex("""(?<!\d)([01]?\d|2[0-3]):([0-5]\d)(?!\d)""")
    private val amounts = Regex("""(?<![\p{L}\d])(\d[\d.]*(?:,\d+)?)\s*(₺|TL|₽|\$|€)(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val prefixAmount = Regex("""(?<![\p{L}\d])([₺$€])\s*(\d[\d.]*(?:,\d+)?)(?!\d)""")
    private val percent = Regex("""%\s*(\d[\d.]*(?:,\d+)?)(?!\d)""")
    private val units = Regex(
        """(?<![\p{L}\d])(\d[\d.]*(?:,\d+)?)\s*(kg|km|cm|mm|ml|mg|g|m|l|°C)(?!\p{L})""",
        RegexOption.IGNORE_CASE
    )
    private val decimal = Regex("""(?<![\p{L}\d])(\d[\d.]*,\d+)(?!\d)""")
    private val integers = Regex("""(?<![\p{L}\d])\d+(?:\.\d{3})*(?!\d)""")
    private val abbreviations = listOf(
        Regex("""\bDr\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "doktor",
        Regex("""\bProf\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "profesör",
        Regex("""\bDoç\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "doçent",
        Regex("""\bSn\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "sayın",
        Regex("""\bvb\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "ve benzeri",
        Regex("""\bvs\.(?=\s|$)""", RegexOption.IGNORE_CASE) to "vesaire"
    )
    private val initialisms = mapOf(
        "TRT" to "te re te", "TBMM" to "te be me me", "SGK" to "se ge ke",
        "TTS" to "te te se", "GPT" to "ge pe te", "AI" to "a i"
    )

    private fun threeDigits(value: Int): String {
        if (value == 0) return ""
        val parts = mutableListOf<String>()
        if (value >= 100) {
            if (value / 100 > 1) parts.add(ones[value / 100])
            parts.add("yüz")
        }
        if (value % 100 >= 10) parts.add(tens[(value % 100) / 10])
        if (value % 10 > 0) parts.add(ones[value % 10])
        return parts.joinToString(" ")
    }

    private fun integer(s: String): String {
        val digits = s.replace(".", "")
        if (digits.isEmpty()) return ""
        // IDs and zero-prefixed codes are spoken digit by digit.
        if ((digits.length > 1 && digits[0] == '0') || digits.length > 12) {
            return digits.map { ones[it - '0'] }.joinToString(" ")
        }
        val value = digits.toLongOrNull() ?: return digits
        if (value == 0L) return "sıfır"
        var left = value
        var group = 0
        val parts = mutableListOf<String>()
        while (left > 0 && group < groups.size) {
            val chunk = (left % 1000).toInt()
            if (chunk > 0) {
                val spoken = if (group == 1 && chunk == 1) {
                    "bin"
                } else {
                    listOf(threeDigits(chunk), groups[group])
                        .filter { it.isNotEmpty() }.joinToString(" ")
                }
                parts.add(0, spoken)
            }
            left /= 1000
            group++
        }
        return parts.joinToString(" ")
    }

    private fun quantity(raw: String): String {
        val pieces = raw.split(',', limit = 2)
        if (pieces.size == 1) return integer(pieces[0])
        return integer(pieces[0]) + " virgül " +
            pieces[1].map { ones[it - '0'] }.joinToString(" ")
    }

    private fun unit(raw: String): String = when (raw.lowercase(tr)) {
        "kg" -> "kilogram"
        "km" -> "kilometre"
        "cm" -> "santimetre"
        "mm" -> "milimetre"
        "ml" -> "mililitre"
        "mg" -> "miligram"
        "g" -> "gram"
        "m" -> "metre"
        "l" -> "litre"
        "°c" -> "derece selsiyus"
        else -> raw
    }

    private fun currency(raw: String): String = when (raw.uppercase(tr)) {
        "₺", "TL" -> "Türk lirası"
        "$" -> "dolar"
        "€" -> "avro"
        "₽" -> "ruble"
        else -> raw
    }

    fun normalize(input: String): String {
        var s = input.replace('\u00a0', ' ')
            .replace(Regex("""[\p{C}]"""), " ")
        // Consume structured values before general numbers.
        s = dates.replace(s) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: 0
            val month = m.groupValues[2].toIntOrNull() ?: 0
            if (day !in 1..31 || month !in 1..12) m.value
            else integer(day.toString()) + " " + months[month] + " " + integer(m.groupValues[3])
        }
        s = clock.replace(s) { m ->
            integer(m.groupValues[1].toInt().toString()) + " " +
                integer(m.groupValues[2].toInt().toString())
        }
        s = prefixAmount.replace(s) { m ->
            quantity(m.groupValues[2]) + " " + currency(m.groupValues[1])
        }
        s = amounts.replace(s) { m ->
            quantity(m.groupValues[1]) + " " + currency(m.groupValues[2])
        }
        s = percent.replace(s) { m -> "yüzde " + quantity(m.groupValues[1]) }
        s = units.replace(s) { m ->
            quantity(m.groupValues[1]) + " " + unit(m.groupValues[2])
        }
        for ((pattern, replacement) in abbreviations) s = pattern.replace(s, replacement)
        // Only known initialisms; do not spell out novel characters' names.
        for ((abbreviation, spoken) in initialisms) {
            val pattern = Regex("""(?<!\p{L})""" + abbreviation + """(?!\p{L})""")
            s = pattern.replace(s, spoken)
        }
        s = decimal.replace(s) { m -> quantity(m.value) }
        s = integers.replace(s) { m -> integer(m.value) }
        // Keep meaningful punctuation, and let the engine do its alphabet filtering.
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
