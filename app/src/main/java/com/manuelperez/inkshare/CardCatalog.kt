package com.manuelperez.inkshare

import org.json.JSONObject
import java.text.Normalizer

data class LorcanaCard(val id: Int, val name: String, val number: Int, val set: String,
    val identifier: String, val image: String, val hasNormal: Boolean, val hasFoil: Boolean,
    val colors: Set<String> = emptySet(), val type: String = "", val setName: String = set, val fullImage: String = image) {
    val search = normalize("$name $identifier $set $setName")
}
fun normalize(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

object CardCatalog {
    fun parse(text: String): List<LorcanaCard> {
        val root = JSONObject(text)
        val array = root.getJSONArray("cards")
        val sets = root.optJSONObject("sets")
        return List(array.length()) { i ->
            val card = array.getJSONObject(i)
            val finishes = card.optJSONArray("foilTypes")
            val types = if (finishes == null) listOf("None", "Silver") else List(finishes.length()) { finishes.getString(it) }
            LorcanaCard(card.getInt("id"), card.getString("fullName"), card.getInt("number"),
                card.getString("setCode"), card.getString("fullIdentifier"),
                card.getJSONObject("images").getString("thumbnail"), "None" in types, types.any { it != "None" },
                card.optString("color", "").split("-").filter { it.isNotBlank() }.toSet(),
                card.optString("type", ""),
                sets?.optJSONObject(card.getString("setCode"))?.optString("name")
                    ?.takeIf { it.isNotBlank() } ?: card.getString("setCode"),
                card.getJSONObject("images").optString("full").takeIf { it.isNotBlank() }
                    ?: card.getJSONObject("images").getString("thumbnail"))
        }
    }
    // OCR suggests candidates only; reprints may have the same name/number.
    fun match(cards: List<LorcanaCard>, text: String): List<LorcanaCard> {
        val normalized = normalize(text)
        val words = normalized.split(" ").toSet()
        val numbers = Regex("(\\d{1,3})\\s*/\\s*\\d{1,3}").findAll(text).map { it.groupValues[1].toInt() }.toSet()
        return cards.map { card ->
            val tokens = normalize(card.name).split(" ").filter { it.length > 2 }
            val coverage = tokens.count { it in words }.toDouble() / tokens.size.coerceAtLeast(1)
            card to (coverage + if (card.number in numbers) 0.8 else 0.0)
        }.filter { it.second >= 0.7 }.sortedByDescending { it.second }.take(15).map { it.first }
    }
}
