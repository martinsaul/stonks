package stonks.engine.sim

import stonks.engine.book.Fill
import stonks.engine.core.Cents
import java.time.Instant

data class Candle(
    val time: Instant,
    val open: Cents,
    val high: Cents,
    val low: Cents,
    val close: Cents,
    val volume: Long,
)

enum class Resolution(val seconds: Long) { S5(5), M1(60), D1(86_400) }

/** Receives every candle as it closes, e.g. to persist it. Called on the sim thread. */
fun interface CandleSink {
    fun onCandle(ticker: String, resolution: Resolution, candle: Candle)
}

/** A bounded, append-only candle series with one in-progress bar. */
class CandleSeries(
    private val maxSize: Int,
    private val onClose: (Candle) -> Unit = {},
) {
    private val closed = ArrayDeque<Candle>()
    private var bucket: Long = Long.MIN_VALUE
    private var time: Instant = Instant.EPOCH
    private var open = 0L
    private var high = 0L
    private var low = 0L
    private var close = 0L
    private var volume = 0L

    val candles: List<Candle> get() = closed
    val current: Candle? get() = if (bucket == Long.MIN_VALUE) null else Candle(time, open, high, low, close, volume)

    fun update(bucketKey: Long, bucketTime: Instant, price: Cents, qty: Long) {
        if (bucketKey != bucket) {
            flush()
            bucket = bucketKey
            time = bucketTime
            open = price; high = price; low = price
            volume = 0
        }
        if (price > high) high = price
        if (price < low) low = price
        close = price
        volume += qty
    }

    fun flush() {
        if (bucket == Long.MIN_VALUE) return
        val candle = Candle(time, open, high, low, close, volume)
        if (maxSize > 0) {
            closed.addLast(candle)
            if (closed.size > maxSize) closed.removeFirst()
        }
        onClose(candle)
        bucket = Long.MIN_VALUE
    }
}

/**
 * Aggregates trades into 5-second, 1-minute and daily (per session) candles. Daily
 * candles are stamped with the session's open time.
 */
class CandleAggregator(private val ticker: String, retention: CandleRetention) {
    /** Optional destination for closed candles. */
    var sink: CandleSink? = null

    val ticks = CandleSeries(retention.ticks) { sink?.onCandle(ticker, Resolution.S5, it) }
    val minutes = CandleSeries(retention.minutes) { sink?.onCandle(ticker, Resolution.M1, it) }
    val days = CandleSeries(retention.days) { sink?.onCandle(ticker, Resolution.D1, it) }
    private var sessionKey = 0L

    fun beginSession(open: Instant) {
        sessionKey = open.epochSecond
    }

    /** Records one tick. With no trades, the bar carries the last price and zero volume. */
    fun onTick(time: Instant, sessionOpen: Instant, fills: List<Fill>, last: Cents) {
        val sec = time.epochSecond
        val minute = sec / 60
        val minuteTime = Instant.ofEpochSecond(minute * 60)
        if (fills.isEmpty()) {
            record(sec, time, minute, minuteTime, sessionOpen, last, 0)
        } else {
            for (f in fills) record(sec, time, minute, minuteTime, sessionOpen, f.price, f.quantity)
        }
        ticks.flush()
    }

    private fun record(sec: Long, time: Instant, minute: Long, minuteTime: Instant, sessionOpen: Instant, price: Cents, qty: Long) {
        ticks.update(sec, time, price, qty)
        minutes.update(minute, minuteTime, price, qty)
        days.update(sessionKey, sessionOpen, price, qty)
    }

    fun endSession() {
        minutes.flush()
        days.flush()
    }
}
