package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UsernameTest {
    @Test fun caseAndWhitespaceUseOneCanonicalName() { assertEquals("player_12", Username.parse("  Player_12  ")) }
    @Test fun invalidNamesAreRejected() {
        for (name in listOf("", "ab", "a".repeat(21), "a/b", "a b", "álvaro", "@player", "👑abc")) assertFalse(name, Username.valid(name))
    }
    @Test fun boundariesAreAccepted() { assertTrue(Username.valid("abc")); assertTrue(Username.valid("a".repeat(20))) }
    @Test fun canonicalNameDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("inkshare", Username.parse("INKSHARE")) }
        finally { Locale.setDefault(original) }
    }
}
