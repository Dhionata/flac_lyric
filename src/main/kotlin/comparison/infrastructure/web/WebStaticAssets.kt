package comparison.infrastructure.web

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Provides static HTML, CSS, and JavaScript assets for the web-based lyrics review interface.
 */
object WebStaticAssets {

    val INDEX_HTML: String
        get() {
            val devFile = File("src/main/resources/web/index.html")
            if (devFile.exists()) {
                return devFile.readText(StandardCharsets.UTF_8)
            }
            val stream = WebStaticAssets::class.java.getResourceAsStream("/web/index.html")
            return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                ?: "<html><body><h1>Error: /web/index.html resource not found</h1></body></html>"
        }
}
