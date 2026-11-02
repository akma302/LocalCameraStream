package dev.localstream.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

class SignalingTlsTest {
    @Test fun requiresFullOutOfBandFingerprint() {
        listOf("", "123456", "G".repeat(64), "A".repeat(63)).forEach {
            assertThrows(IllegalArgumentException::class.java) { PinnedServerTrust(it) }
        }
        assertEquals("AB".repeat(32), normalizeFingerprint("ab:".repeat(32)))
    }

    @Test fun replacedCertificateIsRejectedEvenWithSameName() {
        val first = HeldCertificate.Builder().commonName("Local Camera Stream").build()
        val attacker = HeldCertificate.Builder().commonName("Local Camera Stream").build()
        val trust = PinnedServerTrust(certificateFingerprint(first.certificate))
        trust.checkServerTrusted(arrayOf(first.certificate), "RSA")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(attacker.certificate), "RSA") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "RSA") }
    }

    @Test fun matchingPinDoesNotAcceptExpiredCertificate() {
        val expired = HeldCertificate.Builder().validityInterval(1, 2).build().certificate
        assertThrows(CertificateException::class.java) {
            PinnedServerTrust(certificateFingerprint(expired)).checkServerTrusted(arrayOf(expired), "RSA")
        }
    }

    @Test fun realTlsHandshakeSendsApplicationDataOnlyWithTrustedPin() {
        val identity = SignalingIdentity()
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identity.keyStore, identity.password)
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val pool = Executors.newSingleThreadExecutor()
        val server = ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout = 5_000
        try {
            for (pin in listOf(identity.fingerprint, "00".repeat(32))) {
                val received = pool.submit<Boolean> {
                    try {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5_000
                            val reader = socket.inputStream.bufferedReader()
                            val request = reader.readLine()
                            var header = reader.readLine()
                            while (!header.isNullOrEmpty()) header = reader.readLine()
                            socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                            request?.startsWith("GET /session") == true
                        }
                    } catch (_: IOException) { false }
                }
                val client = OkHttpClient.Builder().pinSignalingServer(pin).callTimeout(5, TimeUnit.SECONDS).build()
                try {
                    val request = Request.Builder().url("https://127.0.0.1:${server.localPort}/session").build()
                    if (pin == identity.fingerprint) client.newCall(request).execute().use { assertEquals("ok", it.body.string()) }
                    else assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                    assertEquals(pin == identity.fingerprint, received.get(6, TimeUnit.SECONDS))
                } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
            }
        } finally { server.close(); pool.shutdownNow() }
    }
}
