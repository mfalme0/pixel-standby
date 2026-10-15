package com.pixelstandby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelstandby.data.Quote

private val PriceUp = Color(0xFF4CAF50)
private val PriceDown = Color(0xFFF44336)
private val PriceStale = Color(0xFF9E9E9E)

/** A single price row: symbol, price, change, and an "updated N min ago" stamp. */
@Composable
fun PriceRow(
    quote: Quote,
    modifier: Modifier = Modifier,
) {
    val changeColor = when {
        quote.stale -> PriceStale
        quote.isUp -> PriceUp
        else -> PriceDown
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "${quote.symbol} (${quote.currency})",
                style = MaterialTheme.typography.titleMedium,
                color = if (quote.stale) PriceStale else MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "updated ${quote.asOf}",
                style = MaterialTheme.typography.bodyLarge,
                color = PriceStale,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = "%.2f".format(quote.price),
                style = MaterialTheme.typography.titleMedium,
                color = if (quote.stale) PriceStale else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.End,
            )
            Text(
                text = "${if (quote.isUp) "+" else ""}%.2f (%.2f%%)".format(
                    quote.change, quote.changePercent
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = changeColor,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** A scrollable list of price rows shown beside the clock. */
@Composable
fun PriceCard(
    quotes: List<Quote>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
    ) {
        quotes.forEach { quote ->
            PriceRow(quote = quote)
        }
    }
}