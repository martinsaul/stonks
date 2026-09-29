package stonks.app.market

import org.slf4j.LoggerFactory
import stonks.engine.sim.Candle
import stonks.engine.sim.CandleSink
import stonks.engine.sim.Resolution
import java.time.Clock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives closed candles from the simulation thread and writes them in batches.
 * Intraday candles older than the retention windows are dropped on arrival, so a
 * multi-year backfill only stores what will be kept.
 */
class CandleWriter(private val store: CandleStore, private val clock: Clock) : CandleSink {
    private val log = LoggerFactory.getLogger(CandleWriter::class.java)
    private val queue = ConcurrentLinkedQueue<TickerCandle>()
    private val queued = AtomicInteger()

    override fun onCandle(ticker: String, resolution: Resolution, candle: Candle) {
        val now = clock.instant()
        val keep = when (resolution) {
            Resolution.S5 -> candle.time.isAfter(now.minus(CandleStore.RETENTION_5S))
            Resolution.M1 -> candle.time.isAfter(now.minus(CandleStore.RETENTION_1M))
            Resolution.D1 -> true
        }
        if (keep) {
            queue.add(TickerCandle(ticker, resolution, candle))
            queued.incrementAndGet()
        }
    }

    val pending: Int get() = queued.get()

    /** Writes everything queued so far. [bulk] uses COPY (for backfills). */
    fun flush(bulk: Boolean = false) {
        while (true) {
            val batch = ArrayList<TickerCandle>(minOf(queued.get(), MAX_BATCH))
            while (batch.size < MAX_BATCH) batch += (queue.poll() ?: break)
            if (batch.isEmpty()) return
            queued.addAndGet(-batch.size)
            try {
                if (bulk) store.bulkInsert(batch) else store.insert(batch)
            } catch (e: Exception) {
                log.error("Failed to write {} candles; they will be retried", batch.size, e)
                queue.addAll(batch)
                queued.addAndGet(batch.size)
                return
            }
        }
    }

    companion object {
        private const val MAX_BATCH = 50_000
    }
}
