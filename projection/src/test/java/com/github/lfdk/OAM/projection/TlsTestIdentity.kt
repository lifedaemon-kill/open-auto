package com.github.lfdk.OAM.projection

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509TrustManager

/** Ephemeral self-signed test identities. No private fixture key is committed or packaged. */
internal class TlsTestIdentity(val pair: KeyPair, val certificate: X509Certificate) {
    val pin: String get() = ProjectionTls.fingerprint(certificate)
    fun client(peer: String?) = ProjectionTls.client(pair.private, arrayOf(certificate), peer)
    fun server(expectedClient: String): SSLEngine {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = error("Server context")
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                if (ProjectionTls.fingerprint(chain[0]) != expectedClient) throw java.security.cert.CertificateException("Wrong client")
            }
        }
        return SSLContext.getInstance("TLSv1.2").apply {
            init(arrayOf(ProjectionIdentityKeyManager(pair.private, arrayOf(certificate))), arrayOf(trust), null)
        }.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
    }

    companion object {
        val phone by lazy { create("Phone") }
        val headUnit by lazy { create("HeadUnit") }

        fun create(name: String, expired: Boolean = false): TlsTestIdentity {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = der(0x30, der(6, byteArrayOf(0x2A, -122, 0x48, -122, -9, 13, 1, 1, 11)), der(5, byteArrayOf()))
            val dn = der(0x30, der(0x31, der(0x30, der(6, byteArrayOf(0x55, 4, 3)), der(12, name.toByteArray()))))
            val validity = der(0x30, der(23, "200101000000Z".toByteArray()),
                der(23, (if (expired) "210101000000Z" else "491231235959Z").toByteArray()))
            val body = der(0x30, der(0xA0, der(2, byteArrayOf(2))), der(2, BigInteger.ONE.toByteArray()),
                algorithm, dn, validity, dn, pair.public.encoded)
            val signature = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update(body); sign() }
            val cert = der(0x30, body, algorithm, der(3, byteArrayOf(0) + signature))
            return TlsTestIdentity(pair, CertificateFactory.getInstance("X.509")
                .generateCertificate(cert.inputStream()) as X509Certificate)
        }

        private fun der(tag: Int, vararg children: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream().apply { children.forEach { write(it) } }.toByteArray()
            val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte())
                else {
                    val raw = BigInteger.valueOf(bytes.size.toLong()).toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
                    byteArrayOf((128 or raw.size).toByte()) + raw
                }
            return byteArrayOf(tag.toByte()) + length + bytes
        }
    }
}
