package comparison

import comparison.application.usecases.OpenFileLocationUseCase
import comparison.domain.ports.SystemFileOpener
import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenFileLocationUseCaseTest {

    private class FakeSystemFileOpener : SystemFileOpener {
        var openedPath: String? = null
        override fun openFileLocation(file: File): Boolean {
            openedPath = file.absolutePath
            return true
        }
    }

    @Test
    fun `test executes file opener with target file`() {
        val fakeOpener = FakeSystemFileOpener()
        val useCase = OpenFileLocationUseCase(fakeOpener)

        val success = useCase.execute("C:/music/track.flac")
        assertTrue(success)
        assertTrue(fakeOpener.openedPath?.contains("track.flac") == true)
    }

    @Test
    fun `test returns false for blank path`() {
        val fakeOpener = FakeSystemFileOpener()
        val useCase = OpenFileLocationUseCase(fakeOpener)

        val success = useCase.execute("")
        assertFalse(success)
    }
}
