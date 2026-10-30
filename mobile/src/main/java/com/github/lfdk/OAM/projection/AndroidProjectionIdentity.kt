package com.github.lfdk.OAM.projection

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Called only from IO. Key material stays in AndroidKeyStore and is never bundled or exported. */
internal object AndroidProjectionIdentity {
    private const val ALIAS = "openauto.projection.client.v1"

    @Synchronized fun client(expectedPeer: String?): ProjectionTlsClient {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val parameters = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setCertificateSubject(X500Principal("CN=OpenAuto Multimedia"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 86400000L))
                .setCertificateNotAfter(Date(now + 10L * 365 * 86400000L))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(parameters)
                generateKeyPair()
            }
        }
        val privateKey = store.getKey(ALIAS, null) as? PrivateKey
            ?: error("Projection identity key is unavailable")
        val chain = store.getCertificateChain(ALIAS)?.map { it as X509Certificate }?.toTypedArray()
            ?: error("Projection identity certificate is unavailable")
        return ProjectionTls.client(privateKey, chain, expectedPeer)
    }
}
