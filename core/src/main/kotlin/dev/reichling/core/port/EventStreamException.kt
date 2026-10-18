package dev.reichling.core.port

/**
 * A failure reported by the streaming platform behind an [EventStreamConsumer].
 *
 * The original platform exception is the [cause], so the core never needs to know platform types.
 */
open class EventStreamException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * A commit was rejected because the consumer no longer owns some of the delivered records,
 * for example after a group rebalance. Those records will be redelivered.
 */
class CommitRejectedException(
    message: String,
    cause: Throwable? = null,
) : EventStreamException(message, cause)
