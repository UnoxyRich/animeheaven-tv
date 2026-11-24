package me.animeheaven.tv.data.network

import java.text.Normalizer
import kotlin.math.log10
import me.animeheaven.tv.data.model.ArtworkLookupRequest

object ArtworkTitleMatcher {
    internal data class Candidate(val titles: List<String>, val year: Int?, val format: String, val popularity: Int)
    private data class Qualifiers(val season: Int?, val part: Int?, val final: Boolean)
    private val apostrophes = Regex("['’‘ʼ]")
    private val punctuation = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val diacritics = Regex("(?<=\\p{IsLatin})\\p{M}+")
    private val seasonPatterns = listOf(
        Regex("\\bseason (\\d+)\\b"), Regex("\\b(\\d+)(?:st|nd|rd|th)? season\\b"),
        Regex("\\b(?:dai )?(\\d+) ki\\b")
    )
    private val romanPattern = Regex("\\b(ii|iii|iv|v|vi)$")
    private val romans = mapOf("ii" to 2, "iii" to 3, "iv" to 4, "v" to 5, "vi" to 6)
    private val partPattern = Regex("\\b(?:part|cour) (\\d+)\\b")
    private val extras = setOf("ova", "ona", "special", "short", "recap")
    private val extraFormats = setOf("ONA", "OVA", "SPECIAL", "MUSIC", "TV_SHORT")
    private val regularFormats = setOf("TV", "MOVIE")

    fun normalize(raw: String): String =
        Normalizer.normalize(Normalizer.normalize(raw, Normalizer.Form.NFKD).replace(diacritics, ""), Normalizer.Form.NFC)
            .lowercase().replace(apostrophes, "").replace(punctuation, " ").trim()

    private fun qualifiers(title: String): Qualifiers {
        val season = seasonPatterns.firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
            ?: romanPattern.find(title)?.groupValues?.get(1)?.let(romans::get)
        val part = partPattern.find(title)?.groupValues?.get(1)?.toIntOrNull()
        return Qualifiers(season, part, title.contains("final season"))
    }

    internal fun bestIndex(request: ArtworkLookupRequest, candidates: List<Candidate>): Int? {
        val queries = listOf(request.title, request.japaneseTitle).map(::normalize).filter(String::isNotEmpty)
        val queryWords = queries.map { it.split(' ').toSet() }
        val expected = queries.map(::qualifiers)
        val season = expected.firstNotNullOfOrNull { it.season }
        val part = expected.firstNotNullOfOrNull { it.part }
        val final = expected.any { it.final }
        val explicitExtra = queryWords.any { words -> words.any(extras::contains) }
        var best: Int? = null
        var bestScore = Double.NEGATIVE_INFINITY
        candidates.forEachIndexed { index, candidate ->
            val aliases = candidate.titles.map(::normalize).filter(String::isNotEmpty)
            val actual = aliases.map(::qualifiers)
            val seasons = actual.mapNotNull { it.season }.toSet()
            val parts = actual.mapNotNull { it.part }.toSet()
            val candidateFinal = actual.any { it.final }
            if (season != null && season !in seasons || part != null && part !in parts || final && !candidateFinal) return@forEachIndexed
            var lexical = 0.0
            var exact = false
            for (alias in aliases) {
                val words = alias.split(' ').toSet()
                queries.forEachIndexed { i, query ->
                    val same = query == alias
                    exact = exact || same
                    val other = queryWords[i]
                    val similarity = words.intersect(other).size.toDouble() / words.union(other).size
                    lexical = maxOf(lexical, (if (same) 100.0 else 0.0) + similarity * 60)
                }
            }
            if (lexical < 30 || !exact && (season == null && seasons.isNotEmpty() || part == null && parts.isNotEmpty())) return@forEachIndexed
            val format = candidate.format.uppercase()
            if (!exact && !explicitExtra && format in extraFormats) return@forEachIndexed
            var score = lexical
            if (season != null || part != null || final && candidateFinal) score += 35
            if (request.year != null && candidate.year != null) score += if (request.year == candidate.year) 20 else -20
            if (!explicitExtra && format in regularFormats) score += 15
            score += minOf(12.0, log10(maxOf(1, candidate.popularity).toDouble()) * 2)
            if (score >= 45 && score > bestScore) {
                best = index
                bestScore = score
            }
        }
        return best
    }
}
