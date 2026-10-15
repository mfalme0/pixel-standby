package com.pixelstandby.data

/** A quote as stored by the repository. */
data class Quote(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val currency: String,
    val asOf: String,
    val source: String,
    val stale: Boolean,
) {
    val isUp: Boolean get() = change > 0
}