package com.manuelperez.inkshare

import java.util.Locale

object Username {
    fun canonical(value: String) = value.trim().lowercase(Locale.ROOT)
    fun valid(value: String) = Regex("[a-z0-9_]{3,20}").matches(value)
    fun parse(value: String): String = canonical(value).also {
        require(valid(it)) { "Usa entre 3 y 20 letras (a-z), números o guion bajo." }
    }
}
