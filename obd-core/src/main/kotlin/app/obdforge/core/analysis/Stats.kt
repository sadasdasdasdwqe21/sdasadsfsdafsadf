package app.obdforge.core.analysis

import kotlin.math.abs
import kotlin.math.sqrt

/** A time series for one channel. Times are milliseconds (session-relative or epoch). */
class Series(val key: String, val times: LongArray, val values: DoubleArray) {
    init { require(times.size == values.size) }
    val size get() = values.size
    fun isEmpty() = values.isEmpty()

    /** Value at time [t] using the most recent sample at or before it (sample-and-hold). */
    fun at(t: Long): Double? {
        if (times.isEmpty() || t < times[0]) return null
        var lo = 0; var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid] <= t) lo = mid else hi = mid - 1
        }
        return values[lo]
    }

    fun slice(fromMs: Long, toMs: Long): Series {
        val idx = times.indices.filter { times[it] in fromMs..toMs }
        return Series(key, LongArray(idx.size) { times[idx[it]] }, DoubleArray(idx.size) { values[idx[it]] })
    }

    companion object {
        fun of(key: String, pairs: List<Pair<Long, Double>>): Series =
            Series(key, LongArray(pairs.size) { pairs[it].first }, DoubleArray(pairs.size) { pairs[it].second })
    }
}

data class SeriesStats(
    val count: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double,
    val stdDev: Double,
    /** Average absolute rate of change, units per second. */
    val meanRateOfChange: Double,
    /** Largest rate of change seen, units per second (signed). */
    val maxRateOfChange: Double,
    val peaks: List<Pair<Long, Double>>,
    val durationMs: Long,
) {
    companion object { val EMPTY = SeriesStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, emptyList(), 0) }
}

object Stats {
    fun mean(v: DoubleArray): Double = if (v.isEmpty()) Double.NaN else v.sum() / v.size
    fun mean(v: List<Double>): Double = if (v.isEmpty()) Double.NaN else v.sum() / v.size

    fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun stdDev(v: DoubleArray): Double {
        if (v.size < 2) return 0.0
        val m = mean(v)
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    fun percentile(v: DoubleArray, p: Double): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.sortedArray()
        val idx = (p / 100.0 * (s.size - 1)).coerceIn(0.0, (s.size - 1).toDouble())
        val lo = idx.toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    fun describe(s: Series, peakCount: Int = 5): SeriesStats {
        if (s.isEmpty()) return SeriesStats.EMPTY
        val v = s.values
        var sumRate = 0.0; var nRate = 0; var maxRate = 0.0
        for (i in 1 until v.size) {
            val dt = (s.times[i] - s.times[i - 1]) / 1000.0
            if (dt <= 0) continue
            val r = (v[i] - v[i - 1]) / dt
            sumRate += abs(r); nRate++
            if (abs(r) > abs(maxRate)) maxRate = r
        }
        return SeriesStats(
            v.size, v.min(), v.max(), mean(v), median(v), stdDev(v),
            if (nRate == 0) 0.0 else sumRate / nRate, maxRate,
            peaks(s, peakCount), s.times.last() - s.times.first(),
        )
    }

    /** Local maxima that stand out from the series (above mean + 1 sd), largest first. */
    fun peaks(s: Series, n: Int): List<Pair<Long, Double>> {
        if (s.size < 3) return emptyList()
        val m = mean(s.values); val sd = stdDev(s.values)
        val out = ArrayList<Pair<Long, Double>>()
        for (i in 1 until s.size - 1) {
            val v = s.values[i]
            if (v >= s.values[i - 1] && v > s.values[i + 1] && v > m + sd) out.add(s.times[i] to v)
        }
        return out.sortedByDescending { it.second }.take(n)
    }

    /** Total time the value spent above (or below) [threshold], using sample-and-hold. */
    fun durationBeyond(s: Series, threshold: Double, above: Boolean): Long {
        var total = 0L
        for (i in 0 until s.size - 1) {
            val v = s.values[i]
            if (if (above) v > threshold else v < threshold) total += s.times[i + 1] - s.times[i]
        }
        return total
    }

    /** Pearson correlation of two series sampled at the times of [a]. */
    fun correlation(a: Series, b: Series): Double? {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until a.size) { val y = b.at(a.times[i]) ?: continue; xs.add(a.values[i]); ys.add(y) }
        if (xs.size < 5) return null
        val mx = mean(xs); val my = mean(ys)
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in xs.indices) { num += (xs[i] - mx) * (ys[i] - my); dx += (xs[i] - mx) * (xs[i] - mx); dy += (ys[i] - my) * (ys[i] - my) }
        if (dx == 0.0 || dy == 0.0) return null
        return num / sqrt(dx * dy)
    }

    /** Sudden changes: steps much larger than this series' normal sample-to-sample change. */
    fun suddenChanges(s: Series, factor: Double = 6.0, minStep: Double = 0.0): List<Pair<Long, Double>> {
        if (s.size < 10) return emptyList()
        val diffs = DoubleArray(s.size - 1) { abs(s.values[it + 1] - s.values[it]) }
        val typical = median(diffs).coerceAtLeast(percentile(diffs, 75.0) / 2)
        val limit = maxOf(typical * factor, minStep, 1e-9)
        return (0 until diffs.size).filter { diffs[it] > limit }.map { s.times[it + 1] to (s.values[it + 1] - s.values[it]) }
    }

    /** Gaps in logging longer than [factor] x the median interval (dropouts / disconnections). */
    fun gaps(s: Series, factor: Double = 5.0): List<Pair<Long, Long>> {
        if (s.size < 5) return emptyList()
        val iv = LongArray(s.size - 1) { s.times[it + 1] - s.times[it] }
        val med = median(DoubleArray(iv.size) { iv[it].toDouble() })
        return (iv.indices).filter { iv[it] > med * factor && iv[it] > 1500 }.map { s.times[it] to s.times[it + 1] }
    }
}
