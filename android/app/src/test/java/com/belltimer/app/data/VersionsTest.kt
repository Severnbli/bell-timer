package com.belltimer.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun comparesNumbersNotText() {
        assertTrue(isNewer("1.10.0", "1.9.3"))
        assertTrue(isNewer("2.0.0", "1.99.99"))
        assertFalse(isNewer("1.4.2", "1.4.2"))
        assertTrue(isNewer("v1.5.0", "1.4.2"))
    }

    @Test
    fun preReleaseSortsBeforeRelease() {
        assertTrue(isNewer("2.1.0", "2.1.0-rc1"))
        assertFalse(isNewer("2.1.0-rc1", "2.1.0"))
    }

    @Test
    fun minimumIsInclusiveAndOptional() {
        assertTrue(meetsMinimum("1.5.0", "1.5.0"))
        assertFalse(meetsMinimum("1.4.2", "1.5.0"))
        assertTrue(meetsMinimum("1.4.2", null))
        assertTrue(meetsMinimum("1.4.2", "garbage"))
    }
}
