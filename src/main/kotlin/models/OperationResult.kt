package models

/**
 * Immutable data class representing the outcome of a batch of operations.
 * Allows services to remain stateless by returning their logs and failures instead of storing them locally.
 *
 * @property changedSet The set of success log messages describing changes made.
 * @property errorSet The set of exceptions captured during execution.
 */
data class OperationResult(
    val changedSet: Set<String> = emptySet(),
    val errorSet: Set<Exception> = emptySet(),
) {
    /**
     * Merges this result with another, returning a new combined [OperationResult].
     *
     * @param other The other result to merge.
     * @return Combined operation result.
     */
    operator fun plus(other: OperationResult): OperationResult {
        return OperationResult(
            changedSet = this.changedSet + other.changedSet,
            errorSet = this.errorSet + other.errorSet
        )
    }
}
