package ui

import javax.swing.JOptionPane

class UserInterface {

    fun showError(message: String) {
        JOptionPane.showMessageDialog(null, message, "Erro", JOptionPane.ERROR_MESSAGE)
    }

    fun showResult(changedSet: Set<String>, errorList: Set<Exception>) {
        if (errorList.isNotEmpty()) {
            val errorMessage = errorList.joinToString("\n") { it.message ?: it.toString() }
            JOptionPane.showMessageDialog(null, errorMessage, "Erros", JOptionPane.ERROR_MESSAGE)
        }

        if (changedSet.isNotEmpty()) {
            val movedMessage = changedSet.joinToString("\n") { it }
            JOptionPane.showMessageDialog(null, movedMessage, "Informação", JOptionPane.INFORMATION_MESSAGE)
        }

        if (errorList.isEmpty() && changedSet.isEmpty()) {
            JOptionPane.showMessageDialog(
                null, "Está tudo no lugar!", "Informação", JOptionPane.INFORMATION_MESSAGE
            )
        }
    }
}
