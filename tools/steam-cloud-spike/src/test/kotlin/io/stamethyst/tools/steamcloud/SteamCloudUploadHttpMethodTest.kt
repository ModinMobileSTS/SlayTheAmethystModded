package io.stamethyst.tools.steamcloud

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SteamCloudUploadHttpMethodTest {
    @Test
    fun steamworksPostIsThree() {
        assertEquals("POST", StsSteamCloudReadOnlySpike.uploadHttpMethod(3))
    }

    @Test
    fun steamworksPutIsFour() {
        assertEquals("PUT", StsSteamCloudReadOnlySpike.uploadHttpMethod(4))
    }

    @Test
    fun headAndOtherMethodsCannotUploadARequestBody() {
        listOf(0, 1, 2, 5, 6, 7, 99).forEach { method ->
            assertThrows(IOException::class.java) {
                StsSteamCloudReadOnlySpike.uploadHttpMethod(method)
            }
        }
    }
}
