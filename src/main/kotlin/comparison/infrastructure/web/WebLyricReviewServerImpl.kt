package comparison.infrastructure.web

import com.sun.net.httpserver.HttpServer
import comparison.domain.ports.WebLyricReviewServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Embedded HTTP server implementation using the standard JDK HttpServer.
 * Automatically selects a free port if the preferred port is already bound.
 */
class WebLyricReviewServerImpl(
    private val router: WebReviewRouter,
) : WebLyricReviewServer {

    private var server: HttpServer? = null
    private var boundPort: Int = 0

    override val isRunning: Boolean
        get() = server != null

    override val serverUrl: String?
        get() = if (isRunning) "http://localhost:$boundPort" else null

    override fun start(preferredPort: Int): Int {
        if (isRunning) return boundPort

        var portToTry = preferredPort
        var created = false

        while (!created && portToTry < preferredPort + 50) {
            try {
                val http = HttpServer.create(InetSocketAddress(portToTry), 0)
                http.createContext("/", router)
                http.executor = Executors.newCachedThreadPool()
                http.start()
                server = http
                boundPort = portToTry
                created = true
            } catch (_: Exception) {
                portToTry++
            }
        }

        if (!created) {
            // Bind to any available dynamic port
            val http = HttpServer.create(InetSocketAddress(0), 0)
            http.createContext("/", router)
            http.executor = Executors.newCachedThreadPool()
            http.start()
            server = http
            boundPort = http.address.port
        }

        return boundPort
    }

    override fun stop() {
        server?.stop(1)
        server = null
        boundPort = 0
    }
}
