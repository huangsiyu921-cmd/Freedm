package app.freedm.engine

/**
 * Global token-bucket rate limiter shared by every segment of every task.
 *
 * A rate of 0 (the default) disables limiting entirely. The bucket refills at
 * `bytesPerSec` and holds at most one second worth of credit, so a burst after
 * an idle period is bounded.
 */
object RateLimiter {

    @Volatile
    private var bytesPerSec: Long = 0

    private var allowance: Double = 0.0
    private var lastNanos: Long = System.nanoTime()

    fun setRate(bytesPerSecond: Long) {
        synchronized(this) {
            bytesPerSec = bytesPerSecond.coerceAtLeast(0)
            allowance = 0.0
            lastNanos = System.nanoTime()
        }
    }

    fun currentRate(): Long = bytesPerSec

    /**
     * Blocks the calling thread until [bytes] may be transferred.
     * Returns immediately when limiting is off.
     */
    fun acquire(bytes: Int) {
        val rate = bytesPerSec
        if (rate <= 0 || bytes <= 0) return
        synchronized(this) {
            val now = System.nanoTime()
            allowance += (now - lastNanos) / 1_000_000_000.0 * rate
            lastNanos = now
            if (allowance > rate) allowance = rate.toDouble()
            allowance -= bytes
            if (allowance < 0) {
                val waitMs = ((-allowance / rate) * 1000.0).toLong().coerceAtLeast(1)
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                allowance = 0.0
                lastNanos = System.nanoTime()
            }
        }
    }
}
