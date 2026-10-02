package kr.ac.kangwon.hai.vibefactory

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HostHttpClientTest {
    @Test
    fun screenAndDownloadClientsReuseOneConnectionWithIndependentDispatchers() {
        ServerSocket(0).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<List<String>> {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream()
                        (1..2).map {
                            val requestLine = reader.readLine()
                            while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
                            output.flush()
                            requestLine
                        }
                    }
                }
                val screen = createVibeHttpClient()
                val download = createVibeHttpClient(20, 600, 120, null)
                assertNotSame(screen.dispatcher, download.dispatcher)
                val url = "http://127.0.0.1:${server.localPort}"
                listOf(screen, download).forEachIndexed { index, client ->
                    client.newCall(Request.Builder().url("$url/request$index").build()).execute().use {
                        assertEquals("OK", it.body!!.string())
                    }
                }
                assertEquals(listOf("GET /request0 HTTP/1.1", "GET /request1 HTTP/1.1"),
                    received.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun monitorAndDownloadKeepTheirExistingTimeouts() {
        val normal = createVibeHttpClient()
        assertEquals(15_000, normal.connectTimeoutMillis)
        assertEquals(120_000, normal.readTimeoutMillis)
        assertEquals(150_000, normal.callTimeoutMillis)
        val monitor = createVibeHttpClient(readTimeoutSeconds = 30, writeTimeoutSeconds = 30, callTimeoutSeconds = null)
        assertEquals(30_000, monitor.readTimeoutMillis)
        assertEquals(0, monitor.callTimeoutMillis)
        val download = createVibeHttpClient(20, 600, 120, null)
        assertEquals(600_000, download.readTimeoutMillis)
        assertEquals(0, download.callTimeoutMillis)
    }
}
