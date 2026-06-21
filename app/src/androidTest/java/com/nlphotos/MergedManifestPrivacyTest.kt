package com.nlphotos

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Privacy-by-construction gate: verifies the fully merged manifest of the
 * installed app does NOT request the INTERNET permission. This is the final
 * proof that no transitive dependency reintroduced network access.
 */
@RunWith(AndroidJUnit4::class)
class MergedManifestPrivacyTest {

    @Test
    fun merged_manifest_has_no_internet() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val info = ctx.packageManager.getPackageInfo(
            ctx.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val perms = info.requestedPermissions ?: emptyArray()
        assertFalse(
            "INTERNET must not be in the merged manifest, but found: ${perms.joinToString()}",
            perms.contains(android.Manifest.permission.INTERNET)
        )
    }
}
