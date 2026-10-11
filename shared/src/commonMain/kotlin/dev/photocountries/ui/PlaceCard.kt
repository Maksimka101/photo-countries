package dev.photocountries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.photocountries.core.flagEmoji

private val STRIPE = 6.dp

/**
 * «Страна и сколько там пробыл» — одна карточка для списка стран и для ленты: цветная полоса страны слева,
 * флаг, название с подписью, справа крупно дни. [minHeight] растягивает карточку (в ленте — по длительности),
 * [extra] — то, что идёт под подписью. Без [stripe] полосы слева нет: в списке стран цвет уже несёт полоска доли.
 */
@Composable
fun PlaceCard(
    country: String,
    color: Color,
    subtitle: String,
    days: Int,
    approximate: Boolean,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp,
    stripe: Boolean = true,
    onClick: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val content = @Composable {
        Row(
            Modifier
                .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
                .heightIn(min = minHeight)
                .then(
                    if (stripe) {
                        Modifier
                            .drawBehind { drawRoundRect(color, Offset.Zero, Size(STRIPE.toPx(), size.height), CornerRadius(STRIPE.toPx() / 2)) }
                            .padding(start = STRIPE + 12.dp)
                    } else {
                        Modifier.padding(start = 4.dp)
                    },
                )
                .padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).background(scheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Text(flagEmoji(country), fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(countryName(country), style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                extra()
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                // «Примерно» сюда не помещается — вместо него знак.
                Text((if (approximate) "≈ " else "") + days, style = MaterialTheme.typography.headlineSmall)
                Text(daysWord(days), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
    val shape = MaterialTheme.shapes.large
    if (onClick != null) {
        Surface(onClick, modifier, color = scheme.surfaceContainer, shape = shape, content = content)
    } else {
        Surface(modifier, color = scheme.surfaceContainer, shape = shape, content = content)
    }
}
