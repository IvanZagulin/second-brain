package ru.secondbrain.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.secondbrain.app.update.ApkIdentity
import ru.secondbrain.app.update.verifyApkIdentity

class ApkIdentityTest {
    private val pkg = "ru.secondbrain.app"
    private val signer = "AB".repeat(32)
    private val other = "CD".repeat(32)

    @Test fun sameSignerAndNewerVersionIsAccepted() {
        assertNull(verifyApkIdentity(ApkIdentity(pkg, 10203, listOf(signer)), pkg, 10202, listOf(signer)))
    }

    @Test fun signerDigestComparisonIgnoresCase() {
        assertNull(verifyApkIdentity(ApkIdentity(pkg, 10203, listOf(signer.lowercase())), pkg, 10202, listOf(signer)))
    }

    @Test fun differentSignerIsRejected() {
        val err = verifyApkIdentity(ApkIdentity(pkg, 10203, listOf(other)), pkg, 10202, listOf(signer))
        assertNotNull(err)
        assertTrue(err!!.contains("Подпись"))
    }

    @Test fun lowerOrEqualVersionCodeIsRejected() {
        assertNotNull(verifyApkIdentity(ApkIdentity(pkg, 10201, listOf(signer)), pkg, 10202, listOf(signer)))
        assertNotNull(verifyApkIdentity(ApkIdentity(pkg, 10202, listOf(signer)), pkg, 10202, listOf(signer)))
    }

    @Test fun otherPackageIsRejected() {
        assertNotNull(verifyApkIdentity(ApkIdentity("com.example.evil", 10203, listOf(signer)), pkg, 10202, listOf(signer)))
        assertNotNull(verifyApkIdentity(ApkIdentity(null, 10203, listOf(signer)), pkg, 10202, listOf(signer)))
    }

    @Test fun unsignedApkIsRejected() {
        assertNotNull(verifyApkIdentity(ApkIdentity(pkg, 10203, emptyList()), pkg, 10202, listOf(signer)))
    }

    @Test fun extraSignerIsRejected() {
        assertNotNull(verifyApkIdentity(ApkIdentity(pkg, 10203, listOf(signer, other)), pkg, 10202, listOf(signer)))
    }
}
