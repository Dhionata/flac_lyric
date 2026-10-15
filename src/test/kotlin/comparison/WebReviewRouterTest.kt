package comparison

import comparison.infrastructure.web.WebReviewRouter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WebReviewRouterTest {

    @Test
    fun `test parseQueryParams with ampersand in path parameter preserves full path`() {
        val originalPath = "C:\\Users\\xiyun\\Music\\Bruno & Marrone\\Sonhos, Amores e Sucessos\\Bruno & Marrone - Por um Minuto (Por un Minuto).flac"
        val encodedParam = URLEncoder.encode(originalPath, StandardCharsets.UTF_8.name())
        val rawQuery = "path=$encodedParam"

        val params = WebReviewRouter.parseQueryParams(rawQuery)

        assertEquals(originalPath, params["path"])
    }

    @Test
    fun `test parseQueryParams with multiple parameters and special characters`() {
        val path = "C:\\Musics\\Cailin Russo & Chrissy Costanza - Phoenix.flac"
        val rawQuery = "id=42&path=" + URLEncoder.encode(path, StandardCharsets.UTF_8.name()) + "&q=" + URLEncoder.encode("Bruno & Marrone", StandardCharsets.UTF_8.name())

        val params = WebReviewRouter.parseQueryParams(rawQuery)

        assertEquals("42", params["id"])
        assertEquals(path, params["path"])
        assertEquals("Bruno & Marrone", params["q"])
    }

    @Test
    fun `test parseQueryParams with null or blank query returns empty map`() {
        assertEquals(emptyMap<String, String>(), WebReviewRouter.parseQueryParams(null))
        assertEquals(emptyMap<String, String>(), WebReviewRouter.parseQueryParams(""))
        assertEquals(emptyMap<String, String>(), WebReviewRouter.parseQueryParams("   "))
    }
}
