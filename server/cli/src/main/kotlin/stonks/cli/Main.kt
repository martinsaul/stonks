package stonks.cli

import stonks.engine.clock.MarketSchedule
import stonks.engine.company.CompanyCatalog
import stonks.engine.core.Rng
import stonks.engine.sim.Backfill
import stonks.engine.sim.Candle
import stonks.engine.sim.Market
import java.io.File
import java.time.Instant
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

private const val USAGE = """Usage: stonks-sim backfill [options]

Simulates market history with the live engine and writes candles as CSV.

Options:
  --seed N        world seed (default 42)
  --sessions N    game days to simulate (default 500)
  --live-at ISO   instant the world goes live (default now)
  --out DIR       output directory (default ./backfill-out)
  --threads N     worker threads (default: CPU count)
"""

fun main(args: Array<String>) {
    if (args.firstOrNull() != "backfill") {
        System.err.println(USAGE)
        exitProcess(if (args.isEmpty() || args[0] in setOf("-h", "--help")) 0 else 2)
    }
    val opts = args.drop(1).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val seed = opts["seed"]?.toLong() ?: 42L
    val sessions = opts["sessions"]?.toInt() ?: 500
    val liveAt = opts["live-at"]?.let(Instant::parse) ?: Instant.now()
    val out = File(opts["out"] ?: "backfill-out")
    val threads = opts["threads"]?.toInt() ?: Runtime.getRuntime().availableProcessors()

    val companies = CompanyCatalog.generate(Rng(Rng.derive(seed, -1)))
    val market = Market(companies, seed = seed)
    val schedule = MarketSchedule()

    println("Simulating $sessions sessions for ${companies.size} companies (seed $seed, $threads threads)…")
    val millis = measureTimeMillis {
        Backfill.run(market, schedule, liveAt, sessions, threads) { i, s ->
            if ((i + 1) % 50 == 0 || i + 1 == sessions) println("  ${i + 1}/$sessions  ${s.open}  regime=${market.regime}")
        }
    }
    println("Done in ${millis / 1000.0}s")
    val regimes = market.regimeHistory.groupingBy { it }.eachCount()
    println("Regime days: " + regimes.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })

    File(out, "daily").mkdirs()
    File(out, "minute").mkdirs()
    File(out, "companies.csv").printWriter().use { w ->
        w.println("ticker,name,sector,shares_outstanding,initial_price,final_price,strategies")
        for (t in market.tickers.values) {
            val c = t.company
            val strategies = t.strategy.history.joinToString(" > ") { it.instance.type.name }
            w.println("${c.ticker},\"${c.name}\",${c.sector},${c.sharesOutstanding},${c.initialPrice / 100.0},${t.last / 100.0},$strategies")
        }
    }
    for (t in market.tickers.values) {
        writeCandles(File(out, "daily/${t.company.ticker}.csv"), t.candles.days.candles)
        writeCandles(File(out, "minute/${t.company.ticker}.csv"), t.candles.minutes.candles)
    }

    println()
    println(String.format("%-6s %-20s %-10s %10s %10s %9s %8s  %s", "TICKER", "NAME", "SECTOR", "START", "END", "RETURN", "VOL", "STRATEGIES"))
    for (t in market.tickers.values.sortedByDescending { it.last.toDouble() / it.company.initialPrice }) {
        val c = t.company
        val days = t.candles.days.candles
        val ret = t.last.toDouble() / c.initialPrice - 1
        println(
            String.format(
                "%-6s %-20s %-10s %10.2f %10.2f %8.1f%% %7.1f%%  %s",
                c.ticker, c.name.take(20), c.sector, c.initialPrice / 100.0, t.last / 100.0,
                ret * 100, annualizedVol(days) * 100,
                t.strategy.history.joinToString(" > ") { it.instance.type.name.lowercase() },
            ),
        )
    }
    println("\nWrote CSVs to ${out.absolutePath}")
}

private fun writeCandles(file: File, candles: List<Candle>) {
    file.printWriter().use { w ->
        w.println("time,open,high,low,close,volume")
        for (c in candles) w.println("${c.time},${c.open / 100.0},${c.high / 100.0},${c.low / 100.0},${c.close / 100.0},${c.volume}")
    }
}

private fun annualizedVol(days: List<Candle>): Double {
    if (days.size < 3) return 0.0
    val r = days.zipWithNext { a, b -> ln(b.close.toDouble() / a.close) }
    val mean = r.average()
    val variance = r.sumOf { (it - mean) * (it - mean) } / (r.size - 1)
    return sqrt(variance * MarketSchedule.GAME_DAYS_PER_YEAR)
}
