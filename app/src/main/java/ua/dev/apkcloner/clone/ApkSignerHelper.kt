package ua.dev.apkcloner.clone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signs the patched (unsigned) clone APK.
 *
 * The signing key/cert is generated once, on-device, inside AndroidKeyStore (no need to ship or
 * generate a .jks file, no Bouncy Castle dependency). AndroidKeyStore's KeyPairGenerator issues a
 * self-signed X.509 certificate for the new key automatically, and java.security.Signature can
 * sign with a KeyStore-backed PrivateKey directly, so apksig works with it unmodified.
 *
 * IMPORTANT: all clones you install from this app should be signed with the SAME key (that's
 * what happens here — one persistent alias). Re-installing/updating a clone with a different key
 * later would fail with an INSTALL_FAILED_UPDATE_INCOMPATIBLE error.
 */
object ApkSignerHelper {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "apk_cloner_signing_key"

    fun signApk(unsignedApk: File, signedApk: File, minSdk: Int) {
        val (privateKey, cert) = getOrCreateSigningKey()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "clone-signer",
            privateKey,
            listOf(cert)
        ).build()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun getOrCreateSigningKey(): Pair<java.security.PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }

        if (!ks.containsAlias(ALIAS)) {
            val now = Date()
            val expiry = Date(now.time + 1000L * 60 * 60 * 24 * 365 * 30) // ~30 years, like debug.keystore

            val spec = KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=ApkCloner"))
                .setCertificateSerialNumber(BigInteger.valueOf(1))
                .setCertificateNotBefore(now)
                .setCertificateNotAfter(expiry)
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
                initialize(spec)
                generateKeyPair()
            }
        }

        val privateKey = ks.getKey(ALIAS, null) as java.security.PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return privateKey to cert
    }

    /** Call once at app start (e.g. from Application#onCreate) so key generation isn't on a hot path. */
    fun warmUp(context: Context) {
        getOrCreateSigningKey()
    }
}
