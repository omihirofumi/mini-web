package com.example.miniweb.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.net.ServerSocket

const val SERVER_PORT = 8080

@Serializable
data class Echo(
    val value: String,
)

data class RequestHead(
    val method: String,
    val target: String,
    val path: String,
    val query: String?,
    val headers: Map<String, String>,
)

typealias PathParams = Map<String, String>

data class Resp(
    val status: Int = 200,
    val contentType: String = "text/plain; charset=utf-8",
    val body: ByteArray,
)

typealias Handler = (head: RequestHead, body: ByteArray, params: PathParams) -> Resp

data class Route(
    val method: String,
    val pattern: String,
    val handler: Handler,
)

private fun isJson(headers: Map<String, String>): Boolean =
    headers.entries.any { it.key.equals("Content-Type", true) && it.value.lowercase().startsWith("application/json") }

private inline fun <reified T> decodeJson(body: ByteArray): T = Json.decodeFromString(String(body, Charsets.UTF_8))

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

// /abc/:id
// /abc/999
// => params[id] = 999
private fun matchPath(
    pattern: String,
    path: String,
): PathParams? {
    val p = pattern.trim('/').split('/').filter { it.isNotEmpty() }
    val s = path.trim('/').split('/').filter { it.isNotEmpty() }
    if (p.size != s.size) return null
    val params = mutableMapOf<String, String>()
    for (i in p.indices) {
        val a = p[i]
        val b = s[i]
        if (a.startsWith(":")) {
            params[a.drop(1)] = b
        } else if (a != b) {
            return null
        }
    }
    return params
}

private val routes: List<Route> =
    listOf(
        Route("GET", "/health") { _, _, _ -> Resp(body = "ok\n".toByteArray()) },
        Route("POST", "/echo") { head, body, _ ->
            if (isJson(head.headers)) {
                val payload = decodeJson<Echo>(body)
                Resp(body = "echo.value=${payload.value}".toByteArray())
            } else {
                Resp(body = "echo.bytes=${body.size}\n".toByteArray())
            }
        },
    )

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
            val bodyText =
                when {
                    head.method == "POST" && head.path == "/echo" && isJson(head.headers) -> {
                        val payload = decodeJson<Echo>(bodyBytes)
                        "echo.value=${payload.value}\n"
                    }
                    else -> {
                        "method=${head.method}, path=${head.path}, body=${bodyBytes.size} bytes\n"
                    }
                }
            val resp: Resp =
                routes.firstNotNullOfOrNull { r ->
                    if (r.method != head.method) {
                        null
                    } else {
                        matchPath(r.pattern, head.path)?.let { params -> r.handler(head, bodyBytes, params) }
                    }
                } ?: Resp(status = 404, body = "Not Found\n".toByteArray())
            val headerBytes =
                buildString {
                    append(
                        "HTTP/1.1 ${resp.status} ${when (resp.status) {
                            200 -> "OK"
                            201 -> "Created"
                            400 -> "Bad Request"
                            404 -> "Not Found"
                            500 -> "Internal Server Error"
                            else -> "OK"
                        }}\r\n",
                    )
                    append("Content-Type: ${resp.contentType}\r\n")
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.ISO_8859_1)

            socket.getOutputStream().use { out ->
                out.write(headerBytes)
                out.write(resp.body)
                out.flush()
            }
        }
    }
}
