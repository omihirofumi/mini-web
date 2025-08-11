package com.example.miniweb.app

import java.net.ServerSocket
import java.nio.charset.StandardCharsets

const val SERVER_PORT = 8080

fun main() {
    println("mini-web: ready")
    serveOnce(SERVER_PORT)
    println("mini-web: done")
}

private fun serveOnce(port: Int) {
    ServerSocket(port).use { server ->
        server.accept().use { socket ->
            val body = "hello body mini-web\n".toByteArray(StandardCharsets.UTF_8)
            val headers =
                buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/plain; charset=utf-8\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }.toByteArray(StandardCharsets.ISO_8859_1)

            socket.getOutputStream().use { out ->
                out.write(headers)
                out.write(body)
                out.flush()
            }
        }
    }
}
