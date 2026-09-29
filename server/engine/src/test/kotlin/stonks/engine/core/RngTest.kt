package stonks.engine.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RngTest {
    @Test
    fun `restored generator continues the same sequence`() {
        val a = Rng(42)
        repeat(17) { a.nextLong() }
        val b = Rng.restore(a.state)
        assertEquals(List(10) { a.nextDouble() }, List(10) { b.nextDouble() })
    }

    @Test
    fun `distributions are sane`() {
        val r = Rng(1)
        val g = List(200_000) { r.gaussian() }
        val mean = g.average()
        val variance = g.sumOf { (it - mean) * (it - mean) } / g.size
        assertTrue(abs(mean) < 0.01 && abs(variance - 1) < 0.02, "mean=$mean var=$variance")
        val ints = List(100_000) { r.nextInt(0, 10) }
        assertTrue(ints.all { it in 0..9 })
        assertTrue(ints.groupingBy { it }.eachCount().values.all { it in 9_000..11_000 })
        assertTrue(abs(List(100_000) { r.poisson(1.0) }.average() - 1.0) < 0.02)
    }
}
