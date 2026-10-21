package com.thefactor1.player.meta

/** A subtitle file found online for the film being played; [language] is a two-letter code when known. */
data class OnlineSubtitle(val url: String, val language: String?)

/** Language codes as subtitle databases write them, and the order results are offered in. */
object SubtitleLanguages {

    /** "eng", "fre", "fra", "pob" (Brazilian Portuguese) or "en" to "en"; unknown codes to null. */
    fun twoLetter(code: String?): String? {
        val clean = code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return null
        if (clean.length == 2) return clean
        return THREE_TO_TWO[clean]
    }

    /**
     * Results in [preferred] languages first, in that order, then the rest; within a language the
     * database's own order (most downloaded first) is kept.
     */
    fun rank(subtitles: List<OnlineSubtitle>, preferred: List<String>, limit: Int): List<OnlineSubtitle> {
        val order = preferred.filter { it.isNotEmpty() }.distinct()
        return subtitles
            .sortedBy { sub -> order.indexOf(sub.language).takeIf { it >= 0 } ?: order.size }
            .take(limit)
    }

    private val THREE_TO_TWO = mapOf(
        "eng" to "en", "spa" to "es", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "ita" to "it", "por" to "pt", "pob" to "pt", "dut" to "nl", "nld" to "nl", "jpn" to "ja",
        "kor" to "ko", "chi" to "zh", "zho" to "zh", "zht" to "zh", "zhe" to "zh", "hin" to "hi",
        "ara" to "ar", "rus" to "ru", "pol" to "pl", "tur" to "tr", "swe" to "sv", "nor" to "no",
        "dan" to "da", "fin" to "fi", "gre" to "el", "ell" to "el", "heb" to "he", "cze" to "cs",
        "ces" to "cs", "hun" to "hu", "rum" to "ro", "ron" to "ro", "vie" to "vi", "tha" to "th",
        "ind" to "id", "ukr" to "uk", "per" to "fa", "fas" to "fa", "bul" to "bg", "hrv" to "hr",
        "srp" to "sr", "slo" to "sk", "slk" to "sk", "slv" to "sl", "may" to "ms", "msa" to "ms",
    )
}
