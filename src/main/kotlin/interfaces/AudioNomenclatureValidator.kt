package interfaces

import java.io.File

/**
 * Contrato de validação (Dependency Inversion Principle)
 * Permite injetar diferentes validadores no futuro sem quebrar o código.
 */
interface AudioNomenclatureValidator {
    fun isValid(file: File, baseDirectory: File): Boolean
}
