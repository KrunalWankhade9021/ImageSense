package com.nlphotos

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * Privacy-by-construction invariant: the app must never declare the INTERNET
 * permission. This guarantees, verifiably from the manifest, that no code path
 * can send photo data off the device.
 */
class ManifestPrivacyTest {

    @Test
    fun manifest_does_not_request_internet() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "INTERNET permission must never be declared",
            manifest.contains("android.permission.INTERNET")
        )
    }
}
