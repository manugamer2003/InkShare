package com.manuelperez.inkshare

import org.junit.Assert.*
import org.junit.Test

class CollectionEntryTest {
    @Test fun foilAndNormalHaveDifferentDocumentKeys() {
        assertEquals("12_normal", CollectionEntry(12, false, 1).key)
        assertEquals("12_foil", CollectionEntry(12, true, 1).key)
    }
    @Test fun previousKeysRoundTrip() {
        assertEquals(CollectionEntry(3242, true, 4), CollectionEntry.fromKey("3242_foil", 4))
    }
    @Test fun addingAndRemovingUseTheServerQuantity() {
        assertEquals(4, CollectionEntry.changed(3, 1))
        assertEquals(0, CollectionEntry.changed(1, -1))
    }
    @Test fun invalidChangesAreRejected() {
        for ((quantity, delta) in listOf(0 to -1, 9999 to 1, 1 to 100, -1 to 1)) {
            assertThrows(IllegalArgumentException::class.java) { CollectionEntry.changed(quantity, delta) }
        }
    }
    @Test fun invalidImportIsRejectedBeforeAnyWrite() {
        for (key in listOf("0_normal", "01_normal", "1_other", "../2_foil")) {
            assertThrows(IllegalStateException::class.java) { CollectionEntry.fromKey(key, 1) }
        }
        assertThrows(IllegalArgumentException::class.java) { CollectionEntry.fromKey("1_foil", -1) }
    }
}
