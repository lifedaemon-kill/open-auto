package com.github.lfdk.OAM.projection

import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

class ProjectionPeerReviewRequired(val fingerprint: String, val changed: Boolean) :
    CertificateException("Projection peer certificate requires confirmation")

class ProjectionTlsClient internal constructor(
    val engine: SSLEngine,
    private val rejection: AtomicReference<ProjectionPeerReviewRequired?>
) {
    internal fun peerRejection(): ProjectionPeerReviewRequired? = rejection.get()
}

object ProjectionTls {
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 255) }

    /** Scoped to this engine. Never installs a provider or changes process-wide trust defaults. */
    fun client(key: PrivateKey, chain: Array<X509Certificate>, expectedPeer: String?): ProjectionTlsClient {
        require(chain.isNotEmpty())
        require(expectedPeer == null || expectedPeer.matches(Regex("[0-9A-F]{64}")))
        val rejection = AtomicReference<ProjectionPeerReviewRequired?>()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("This TLS context is a projection client")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing projection certificate")
                val actual = fingerprint(leaf)
                if (expectedPeer == null || !MessageDigest.isEqual(actual.toByteArray(), expectedPeer.toByteArray())) {
                    val review = ProjectionPeerReviewRequired(actual, expectedPeer != null)
                    rejection.set(review)
                    throw review
                }
                leaf.checkValidity()
            }
        }
        val context = SSLContext.getInstance("TLSv1.2").apply {
            init(arrayOf(ProjectionIdentityKeyManager(key, chain)), arrayOf(trust), null)
        }
        val engine = context.createSSLEngine().apply {
            useClientMode = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        return ProjectionTlsClient(engine, rejection)
    }
}

internal class ProjectionIdentityKeyManager(
    private val key: PrivateKey,
    private val certificates: Array<X509Certificate>
) : X509ExtendedKeyManager() {
    private val alias = "openauto-projection"
    private fun supports(type: String?): Boolean = type == certificates[0].publicKey.algorithm
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (supports(keyType)) arrayOf(alias) else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        getClientAliases(keyType, issuers)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType?.any(::supports) == true) alias else null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (supports(keyType)) alias else null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseClientAlias(keyType, issuers, null)
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        chooseServerAlias(keyType, issuers, null)
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) certificates.copyOf() else null
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
}
