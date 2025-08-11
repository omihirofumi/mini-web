package com.example.miniweb.app

import java.io.InputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

const val SERVER_PORT = 8080

data class RequestHead(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
)

fun parseHead(raw: ByteArray): Pair<RequestHead, Int>? {
    val end = indexOfCrlfCrlf(raw)
    if (end < 0) return null
    val headText = String(raw, 0, end, Charsets.ISO_8859_1)
    val lines = headText.split("\r\n")
    val parts = lines.firstOrNull()?.split(' ') ?: return null
    if (parts.size < 3) return null
    val method = parts[0]
    val target = parts[1]
    val (path, query) = target.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
    val headers =
        lines
            .drop(1)
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i > 0) line.take(i).trim() to line.substring(i + 1).trim() else null
            }.toMap()
    return RequestHead(method, target, path, query, headers) to (end + 4)
}

private fun indexOfCrlfCrlf(b: ByteArray): Int {
    for (i in 0 until b.size - 3) {
        if (b[i] == 13.toByte() && b[i + 1] == 10.toByte() && b[i + 2] == 13.toByte() && b[i + 3] == 10.toByte()) return i
    }
    return -1
}

private fun readUntilHeaderEnd(
    input: InputStream,
    maxBytes: Int = 64 * 1024,
): ByteArray {
    val buf = ByteArray(8192)
    val out = java.io.ByteArrayOutputStream()
    while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        val bytes = out.toByteArray()
        if (indexOfCrlfCrlf(bytes) >= 0) return bytes
        if (bytes.size > maxBytes) error("header too large")
    }
    return out.toByteArray()
}

private fun contentLengthOf(headers: Map<String, String>): Int? =
    headers.entries
        .firstOrNull { it.key.equals("Content-Length", true) }
        ?.value
        ?.trim()
        ?.toIntOrNull()

private fun readExactly(
    input: java.io.InputStream,
    n: Int,
): ByteArray {
    val buf = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = input.read(buf, read, n - read)
        if (r <= 0) error("unexpected EOF while reading body")
        read += r
    }
    return buf
}

private fun collectBody(
    input: java.io.InputStream,
    raw: ByteArray,
    offset: Int,
    contentLength: Int,
    maxBody: Int = 1_048_576,
): ByteArray {
    require(contentLength <= maxBody) { "body too large: $contentLength" }
    val already = if (raw.size > offset) raw.copyOfRange(offset, raw.size) else ByteArray(0)
    return if (already.size >= contentLength) {
        already.copyOf(contentLength) // ヘッダ読取時に body も来ていたケース
    } else {
        val remain = contentLength - already.size
        already + readExactly(input, remain)
    }
}

fun main() {
    println("mini-web: ready")
    serveOnce(SERVER_PORT)
    println("mini-web: done")
}

private fun serveOnce(port: Int) {
    ServerSocket(port).use { server ->
        server.accept().use { socket ->
            val input = socket.getInputStream()
            val raw = readUntilHeaderEnd(input)
            val (head, offset) = parseHead(raw) ?: error("bad request head")
            println(">> ${head.method} ${head.target}")

            val contentLength = contentLengthOf(head.headers) ?: 0
            val bodyBytes =
                if (contentLength > 0) {
                    collectBody(input, raw, offset, contentLength)
                } else {
                    ByteArray(0)
                }
            val bodyText = "method=${head.method}, body=${bodyBytes.size} bytes\n"
            val body = bodyText.toByteArray(Charsets.UTF_8)
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
