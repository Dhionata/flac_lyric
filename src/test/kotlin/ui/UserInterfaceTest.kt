package ui

import interfaces.UserInterface
import org.junit.jupiter.api.Test

class UserInterfaceTest {

    val userInterfaceImpl: UserInterface = UserInterfaceImpl()

    @Test
    fun option() {
        userInterfaceImpl.option()
    }
}
