package com.ecommerce.echo

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

private const val OK = 200

/** A minimal provider: answers GET /echo with the JSON body in [body]. */
fun startEcho(body: String): HttpServer =
    HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/echo") { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
