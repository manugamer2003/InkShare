package com.manuelperez.inkshare

import android.content.Context
import org.json.JSONObject

class CollectionStore(context: Context, uid: String = Session.uid()) {
    private val prefs = context.applicationContext.getSharedPreferences("inkshare_collection_$uid", Context.MODE_PRIVATE)
    fun counts(): Map<String, Int> {
        val json = JSONObject(prefs.getString("counts", "{}") ?: "{}")
        return json.keys().asSequence().associateWith { json.getInt(it) }
    }
    fun save(values: Map<String, Int>) {
        // One atomic preference entry prevents partial restores.
        check(prefs.edit().putString("counts", JSONObject(values).toString()).commit()) { "No se pudo guardar en el dispositivo" }
    }
    companion object { fun key(id: Int, foil: Boolean) = "${id}_${if (foil) "foil" else "normal"}" }
}
