package com.example.telepathytag

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TagBinding(
    val mac: String,
    val name: String
)

class TagRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tag_bindings", Context.MODE_PRIVATE)

    private val _bindings = MutableStateFlow(loadBindings())
    val bindings: StateFlow<List<TagBinding>> = _bindings.asStateFlow()

    private fun loadBindings(): List<TagBinding> {
        val json = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<TagBinding>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(TagBinding(
                mac = obj.getString("mac"),
                name = obj.getString("name")
            ))
        }
        return list
    }

    fun addBinding(mac: String, name: String) {
        val list = _bindings.value.toMutableList()
        // Replace existing binding for same MAC
        list.removeAll { it.mac == mac }
        list.add(TagBinding(mac = mac, name = name))
        saveList(list)
    }

    fun removeBinding(mac: String) {
        val list = _bindings.value.toMutableList()
        list.removeAll { it.mac == mac }
        saveList(list)
    }

    private fun saveList(list: List<TagBinding>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("mac", it.mac)
                put("name", it.name)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
        _bindings.value = list
    }
}
