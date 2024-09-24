package ui

import interfaces.UserInterface
import org.junit.jupiter.api.Test

class UserInterfaceImplTest {

    val userInterface: UserInterface = UserInterfaceImpl()

    @Test
    fun option() {
        userInterface.option()
    }
}
