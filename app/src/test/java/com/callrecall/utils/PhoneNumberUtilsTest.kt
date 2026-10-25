package com.callrecall.utils

import org.junit.Assert.*
import org.junit.Test

class PhoneNumberUtilsTest {
    @Test fun indianPrefixesResolveToOneKey() {
        val variants = listOf("9876543210", "09876543210", "919876543210", "+91 98765-43210", "0091 9876543210")
        variants.forEach { assertEquals(it, "+919876543210", PhoneNumberUtils.normalize(it, "IN")) }
    }

    @Test fun explicitForeignCountryOverridesLocalRegion() {
        assertEquals("+442079460018", PhoneNumberUtils.normalize("+44 (20) 7946 0018", "IN"))
        assertEquals("+16502530000", PhoneNumberUtils.normalize("+1 650-253-0000", "IN"))
        assertEquals("+33142345678", PhoneNumberUtils.normalize("0033 1 42 34 56 78", "IN"))
    }

    @Test fun nationalTrunkPrefixesFollowCountryRules() {
        assertEquals("+442079460018", PhoneNumberUtils.normalize("020 7946 0018", "GB"))
        assertEquals("+390236618300", PhoneNumberUtils.normalize("02 36618 300", "IT"))
        assertEquals("+16502530000", PhoneNumberUtils.normalize("(650) 253-0000", "US"))
        assertEquals("+442079460018", PhoneNumberUtils.normalize("011 44 20 7946 0018", "US"))
    }

    @Test fun invalidUnknownAndServiceNumbersAreNotInvented() {
        listOf("", "123", "unknown", "private", "*123#", "+999123456789", "+16502530000 ext 12").forEach {
            assertEquals(it, "", PhoneNumberUtils.normalize(it, "IN"))
        }
    }

    @Test fun unknownRegionRequiresAnExplicitCountryCode() {
        assertEquals("", PhoneNumberUtils.normalize("9876543210", "ZZ"))
        assertEquals("+919876543210", PhoneNumberUtils.normalize("+919876543210", "ZZ"))
    }

    @Test fun internationalKeysAreStableAcrossDeviceRegions() {
        val key = PhoneNumberUtils.normalize("9876543210", "IN")
        listOf("IN", "GB", "US", "ZZ").forEach { assertEquals(key, PhoneNumberUtils.normalize(key, it)) }
    }
}
