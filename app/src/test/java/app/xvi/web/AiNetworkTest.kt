package app.xvi.web

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiNetworkTest {
    @Test fun customChatAndModelValidationUseTheSuppliedProxy() {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 5000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val received = executor.submit<List<String>> {
                    (0..1).map { index ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val reader = socket.getInputStream().bufferedReader()
                            val line = reader.readLine()
                            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                            val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                            repeat(length) { reader.read() }
                            val body = if (index == 0) """{"data":[{"id":"vision"}]}""" else
                                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n\n"
                            socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                            line
                        }
                    }
                }
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))
                runBlocking {
                    val base = "http://custom-ai.invalid/v1"
                    assertEquals(1, DeepSeek.modelCount(base, "test-key", proxy))
                    val answer = StringBuilder()
                    DeepSeek.chat(base, "test-key", "vision", listOf(DeepSeek.Message("user", "hello")), proxy = proxy) { answer.append(it) }
                    assertEquals("ok", answer.toString())
                }
                val lines = received.get(5, TimeUnit.SECONDS)
                assertTrue(lines[0].startsWith("GET http://custom-ai.invalid/v1/models "))
                assertTrue(lines[1].startsWith("POST http://custom-ai.invalid/v1/chat/completions "))
            } finally { executor.shutdownNow() }
        }
    }
}
