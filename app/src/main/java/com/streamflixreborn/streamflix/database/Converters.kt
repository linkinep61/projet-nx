package com.streamflixreborn.streamflix.database

import androidx.room.TypeConverter
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.toCalendar
import java.util.Calendar

class Converters {

    @TypeConverter
    fun fromCalendar(value: Calendar?): String? {
        return value?.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }

    @TypeConverter
    fun toCalendar(value: String?): Calendar? {
        return value?.toCalendar()
    }


    @TypeConverter
    fun fromTvShow(value: TvShow?): String? {
        return value?.id
    }

    @TypeConverter
    fun toTvShow(value: String?): TvShow? {
        return value?.let { TvShow(it, "") }
    }


    @TypeConverter
    fun fromSeason(value: Season?): String? {
        if (value == null) return null
        // Encode season number alongside ID so it survives DB round-trip.
        // Separator "§§" chosen because no provider ID contains it.
        // Legacy data (without "§§") falls back to number=0 on read.
        return if (value.number > 0) "${value.id}§§${value.number}" else value.id
    }

    @TypeConverter
    fun toSeason(value: String?): Season? {
        if (value == null) return null
        // Parse "id§§number" format; legacy strings without "§§" get number=0.
        val sep = value.lastIndexOf("§§")
        return if (sep > 0 && sep < value.length - 2) {
            val id = value.substring(0, sep)
            val num = value.substring(sep + 2).toIntOrNull() ?: 0
            Season(id, num)
        } else {
            Season(value, 0)
        }
    }
}