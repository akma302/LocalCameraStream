package dev.localstream.network

import okhttp3.OkHttpClient
import okhttp3.tls.HeldCertificate
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** In-memory identity for one broadcast. Its fingerprint must be transferred out of band. */
class SignalingIdentity {
    private val held = HeldCertificate.Builder().commonName("Local Camera Stream")
        .rsa2048().duration(7, TimeUnit.DAYS).build()
    val fingerprint: String = certificateFingerprint(held.certificate)
    val password: CharArray = java.util.UUID.randomUUID().toString().toCharArray()
    val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, password)
        setKeyEntry("host", held.keyPair.private, password, arrayOf(held.certificate))
    }
}

fun certificateFingerprint(certificate: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02X".format(it) }

fun normalizeFingerprint(value: String): String = value.filterNot { it.isWhitespace() || it == ':' }.uppercase().also {
    require(it.matches(Regex("[0-9A-F]{64}"))) { "Введите SHA-256 отпечаток с экрана сервера (64 символа)" }
}

/** The exact leaf certificate is the identity; DNS/IP and NSD names are only locators. */
class PinnedServerTrust(fingerprint: String) : X509TrustManager {
    private val expected = normalizeFingerprint(fingerprint).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not supported")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("Missing server certificate")
        leaf.checkValidity()
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(leaf.encoded)))
            throw CertificateException("Server fingerprint mismatch")
    }
}

fun OkHttpClient.Builder.pinSignalingServer(fingerprint: String): OkHttpClient.Builder = apply {
    val trust = PinnedServerTrust(fingerprint)
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
    sslSocketFactory(ssl.socketFactory, trust)
    // Never accept an arbitrary host: identity was verified against the out-of-band pin
    // by the trust manager before this callback. Recheck it for pooled connections.
    hostnameVerifier { _, session ->
        runCatching { trust.checkServerTrusted(session.peerCertificates.map { it as X509Certificate }.toTypedArray(), ""); true }
            .getOrDefault(false)
    }
    followRedirects(false)
    followSslRedirects(false)
}
