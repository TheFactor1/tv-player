package com.thefactor1.player.ui

import java.util.Locale

/**
 * Turns a track's language tag and its free-form label (often a release group plus shorthand, like
 * "BTM LA") into a clean name such as "Spanish (Latin America)" or "English (SDH)". Kept free of
 * Android types so it can be unit tested.
 */
object TrackNames {

    /**
     * @param languageTag the track's BCP 47 or ISO 639 tag, if any ("es-419", "spa", "und").
     * @param label the track's own name, if any.
     * @return the clean name, or null when neither tells us anything; callers number those.
     */
    fun name(
        languageTag: String?,
        label: String?,
        forced: Boolean = false,
        hearingImpaired: Boolean = false,
        commentary: Boolean = false,
        displayLocale: Locale = Locale.getDefault(),
    ): String? {
        val words = label.orEmpty()
            .split(' ', '-', '_', '.', '/', '[', ']', '(', ')', ',', '|')
            .filter { it.isNotBlank() }
            .map { it.lowercase(Locale.ROOT) }

        val tag = languageTag?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) != "und" }
        var language = tag?.let { languageName(it, displayLocale) }
        var region = tag?.let { regionName(it) }
        val notes = linkedSetOf<String>()

        for (word in words) {
            if (language == null) LANGUAGE_WORDS[word]?.let { language = languageName(it, displayLocale) }
            REGION_WORDS[word]?.let { if (region == null) region = it }
            NOTE_WORDS[word]?.let { notes += it }
        }
        if (forced) notes += FORCED
        if (hearingImpaired) notes += SDH
        if (commentary) notes += COMMENTARY

        val qualifiers = listOfNotNull(region) + notes
        return when {
            language != null && qualifiers.isNotEmpty() -> "$language (${qualifiers.joinToString(", ")})"
            language != null -> language
            qualifiers.isNotEmpty() -> qualifiers.joinToString(", ")
            else -> null
        }
    }

    private fun languageName(tag: String, displayLocale: Locale): String? {
        val locale = Locale.forLanguageTag(tag.replace('_', '-'))
        val name = locale.getDisplayLanguage(displayLocale)
        if (name.isBlank() || name.equals(locale.language, ignoreCase = true)) return null
        return name.replaceFirstChar { it.titlecase(displayLocale) }
    }

    private fun regionName(tag: String): String? {
        val region = Locale.forLanguageTag(tag.replace('_', '-')).country
        return when (region.uppercase(Locale.ROOT)) {
            "" -> null
            "419" -> LATIN_AMERICA
            "ES" -> if (tag.startsWith("es", ignoreCase = true)) EUROPE else null
            "BR" -> "Brazil"
            "PT" -> "Portugal"
            "CA" -> "Canada"
            "MX" -> "Mexico"
            "GB" -> "UK"
            "US" -> null
            else -> Locale("", region).getDisplayCountry(Locale.ENGLISH).ifBlank { null }
        }
    }

    private const val FORCED = "Forced"
    private const val SDH = "SDH"
    private const val COMMENTARY = "Commentary"
    private const val LATIN_AMERICA = "Latin America"
    private const val EUROPE = "Europe"

    private val LANGUAGE_WORDS = mapOf(
        "english" to "en", "eng" to "en",
        "spanish" to "es", "espanol" to "es", "español" to "es", "spa" to "es", "castellano" to "es", "latino" to "es",
        "french" to "fr", "francais" to "fr", "français" to "fr", "fre" to "fr", "fra" to "fr",
        "german" to "de", "deutsch" to "de", "ger" to "de", "deu" to "de",
        "italian" to "it", "italiano" to "it", "ita" to "it",
        "portuguese" to "pt", "portugues" to "pt", "português" to "pt", "por" to "pt",
        "dutch" to "nl", "nederlands" to "nl", "dut" to "nl",
        "japanese" to "ja", "jpn" to "ja",
        "korean" to "ko", "kor" to "ko",
        "chinese" to "zh", "chi" to "zh", "zho" to "zh",
        "russian" to "ru", "rus" to "ru",
        "arabic" to "ar", "ara" to "ar",
        "hindi" to "hi", "hin" to "hi",
        "polish" to "pl", "pol" to "pl",
        "turkish" to "tr", "tur" to "tr",
        "swedish" to "sv", "swe" to "sv",
        "danish" to "da", "dan" to "da",
        "norwegian" to "no", "nor" to "no",
        "finnish" to "fi", "fin" to "fi",
    )

    private val REGION_WORDS = mapOf(
        "la" to LATIN_AMERICA, "lat" to LATIN_AMERICA, "latam" to LATIN_AMERICA, "latino" to LATIN_AMERICA,
        "latin" to LATIN_AMERICA, "419" to LATIN_AMERICA,
        "eu" to EUROPE, "eur" to EUROPE, "euro" to EUROPE, "european" to EUROPE, "castellano" to EUROPE,
        "castilian" to EUROPE, "spain" to EUROPE,
        "br" to "Brazil", "brazil" to "Brazil", "brazilian" to "Brazil",
        "canadian" to "Canada", "quebec" to "Canada",
        "uk" to "UK", "british" to "UK",
    )

    private val NOTE_WORDS = mapOf(
        "sdh" to SDH, "cc" to SDH, "hi" to SDH, "hoh" to SDH,
        "forced" to FORCED, "foreign" to FORCED,
        "commentary" to COMMENTARY,
        "signs" to "Signs & Songs", "songs" to "Signs & Songs",
        "simplified" to "Simplified", "chs" to "Simplified",
        "traditional" to "Traditional", "cht" to "Traditional",
    )
}
