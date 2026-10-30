package com.github.lfdk.OAM.projection

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProjectionVersionProbeTest {
    private fun server() = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private fun endpoint(server: ServerSocket) =
        ProjectionEndpoint(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))

    @Test fun sendsOneRealVersionRequestAndWaitsForCorrectResponse() {
        val executor = Executors.newSingleThreadExecutor()
        server().use { server ->
            try {
                val phone = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val request = AapFrameCodec.read(socket.getInputStream())
                        assertEquals(0, request.channel)
                        assertEquals(3, request.flags)
                        assertArrayEquals(byteArrayOf(0, 1, 0, 1, 0, 2), request.payload)
                        // An unrelated control packet is not proof of a successful version exchange.
                        AapFrameCodec.write(socket.getOutputStream(), AapFrame(0, 3, byteArrayOf(0, 11, 8, 0)))
                        val response = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 6, 0, 0)
                        response.forEach { socket.getOutputStream().write(it.toInt() and 255); socket.getOutputStream().flush() }
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                val result = ProjectionVersionProbe(2000).execute(endpoint(server))
                assertEquals(NegotiatedVersion(1, 6, 0), result)
                phone.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun cancellationClosesBlockedReadPromptly() {
        val executor = Executors.newFixedThreadPool(2)
        val received = CountDownLatch(1)
        val probe = ProjectionVersionProbe(10000)
        server().use { server ->
            try {
                val phone = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        AapFrameCodec.read(socket.getInputStream())
                        received.countDown()
                        assertEquals(-1, socket.getInputStream().read())
                    }
                }
                val call = executor.submit<Throwable?> {
                    try { probe.execute(endpoint(server)); null }
                    catch (failure: IOException) { failure }
                }
                assertTrue(received.await(3, TimeUnit.SECONDS))
                probe.close()
                assertNotNull(call.get(2, TimeUnit.SECONDS))
                phone.get(3, TimeUnit.SECONDS)
            } finally { probe.close(); executor.shutdownNow() }
        }
    }

    @Test fun closesOnTruncatedResponse() {
        val executor = Executors.newSingleThreadExecutor()
        server().use { server ->
            try {
                val phone = executor.submit {
                    server.accept().use { socket ->
                        AapFrameCodec.read(socket.getInputStream())
                        socket.getOutputStream().write(byteArrayOf(0, 3, 0, 8, 0, 2))
                    }
                }
                assertThrows(EOFException::class.java) { ProjectionVersionProbe(2000).execute(endpoint(server)) }
                phone.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun totalDeadlineStopsSlowDripFeeds() {
        val executor = Executors.newSingleThreadExecutor()
        server().use { server ->
            try {
                executor.submit {
                    server.accept().use { socket ->
                        try {
                            AapFrameCodec.read(socket.getInputStream())
                            val response = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 2, 0, 0)
                            response.forEach { byte ->
                                socket.getOutputStream().write(byte.toInt() and 255)
                                socket.getOutputStream().flush()
                                Thread.sleep(100)
                            }
                        } catch (_: IOException) { /* Probe closes the peer when its deadline expires. */ }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }
                assertThrows(SocketTimeoutException::class.java) { ProjectionVersionProbe(350).execute(endpoint(server)) }
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun aCancelledCallCannotOpenAnotherSocket() {
        val probe = ProjectionVersionProbe()
        probe.close()
        assertThrows(IOException::class.java) { probe.execute(ProjectionEndpoint.parse("192.168.1.2")) }
    }
}
