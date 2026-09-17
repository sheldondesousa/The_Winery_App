package com.sheldondesousa.uncork.data.profile

import androidx.room.TypeConverter
import org.json.JSONArray

class StringListConverter {
    @TypeConverter
    fun fromJson(value: String): List<String> {
        val array = JSONArray(value)
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(array.getString(index))
            }
        }
    }

    @TypeConverter
    fun toJson(value: List<String>): String = JSONArray(value).toString()
}
