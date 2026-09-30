package stonks.engine.snapshot

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Binary world snapshots. Written only while the market is closed, so no market-maker
 * ladder or in-progress candles need saving. Bump [VERSION] on any layout change and
 * keep readers for older versions once real worlds depend on them.
 */
object Snapshot {
    const val MAGIC = 0x53544E4B // "STNK"
    const val VERSION = 4
    /** Oldest version still readable (v1 predates trading state). */
    const val MIN_VERSION = 1
}

internal fun DataOutputStream.writeNullableLong(v: Long?) {
    writeBoolean(v != null)
    if (v != null) writeLong(v)
}

internal fun DataInputStream.readNullableLong(): Long? = if (readBoolean()) readLong() else null

internal fun DataOutputStream.writeDoubles(values: DoubleArray) {
    writeInt(values.size)
    values.forEach { writeDouble(it) }
}

internal fun DataInputStream.readDoubles(): DoubleArray = DoubleArray(readInt()) { readDouble() }

internal inline fun <T> DataOutputStream.writeList(items: List<T>, write: DataOutputStream.(T) -> Unit) {
    writeInt(items.size)
    items.forEach { write(it) }
}

internal inline fun <T> DataInputStream.readList(readItem: DataInputStream.() -> T): List<T> = List(readInt()) { readItem(this) }
