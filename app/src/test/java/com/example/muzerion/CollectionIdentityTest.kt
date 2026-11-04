package com.example.muzerion

import org.junit.Assert.*
import org.junit.Test

class CollectionIdentityTest {
    @Test fun collaboratorsAppearIndividuallyWithoutDuplicatingAnArtist() {
        assertEquals(listOf("Alpha", "Beta", "Gamma"), artistNames(" Alpha, Beta feat. Gamma; alpha "))
    }
    @Test fun artistPunctuationIsPreserved() {
        assertEquals(listOf("AC/DC", "Earth Wind & Fire"), artistNames("AC/DC; Earth Wind & Fire"))
        assertEquals(listOf("AC/DC"), artistNames("AC/DC"))
    }
    @Test fun unknownArtistsRemainDiscoverable() {
        assertEquals(listOf("Неизвестный исполнитель"), artistNames(" ; , "))
    }
    @Test fun albumTitlesByDifferentArtistsDoNotMerge() {
        assertNotEquals(albumIdentity("Home", "Alpha"), albumIdentity("Home", "Beta"))
        assertEquals(albumIdentity(" Home ", "ALPHA"), albumIdentity("home", "alpha"))
        assertNotEquals(albumIdentity("a:b", "c"), albumIdentity("a", "b:c"))
    }
}
