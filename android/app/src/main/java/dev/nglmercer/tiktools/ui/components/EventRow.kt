package dev.nglmercer.tiktools.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.nglmercer.tiktools.core.model.*
import dev.nglmercer.tiktools.R
import java.text.SimpleDateFormat
import java.util.*

fun LiveEvent.Category.icon(): ImageVector =
    when (this) {
        LiveEvent.Category.CHAT -> Icons.Outlined.ChatBubbleOutline
        LiveEvent.Category.EMOTE -> Icons.Outlined.EmojiEmotions
        LiveEvent.Category.GIFT -> Icons.Outlined.CardGiftcard
        LiveEvent.Category.LIKE -> Icons.Outlined.FavoriteBorder
        LiveEvent.Category.FOLLOW -> Icons.Outlined.PersonAddAlt
        LiveEvent.Category.SHARE -> Icons.Outlined.Share
        LiveEvent.Category.SOCIAL -> Icons.Outlined.PeopleOutline
        LiveEvent.Category.JOIN,
        LiveEvent.Category.MEMBER -> Icons.Outlined.PeopleOutline
        LiveEvent.Category.ROOM -> Icons.Outlined.Visibility
        LiveEvent.Category.UNKNOWN -> Icons.Outlined.HelpOutline
    }

@Composable
fun LiveEvent.summary(): String =
    when (category) {
        LiveEvent.Category.CHAT -> text
        LiveEvent.Category.EMOTE -> stringResource(R.string.event_emote_summary, count)
        LiveEvent.Category.GIFT ->
            stringResource(
                R.string.event_gift_summary,
                text.ifEmpty { stringResource(R.string.gift_fallback) },
                count,
                diamonds * count.coerceAtLeast(1),
            )
        LiveEvent.Category.LIKE ->
            pluralStringResource(R.plurals.event_likes, count.toInt(), count)
        LiveEvent.Category.FOLLOW -> stringResource(R.string.event_followed)
        LiveEvent.Category.SHARE -> stringResource(R.string.event_shared)
        LiveEvent.Category.SOCIAL -> stringResource(R.string.event_social_summary, count)
        LiveEvent.Category.JOIN -> stringResource(R.string.event_joined)
        LiveEvent.Category.MEMBER -> stringResource(R.string.event_member_summary, count)
        LiveEvent.Category.ROOM ->
            pluralStringResource(
                R.plurals.event_viewers,
                count.toInt(),
                localizedCompact(count),
            )
        LiveEvent.Category.UNKNOWN -> text.ifEmpty { stringResource(R.string.event_unknown) }
    }

fun eventTime(at: Long, locale: Locale = Locale.getDefault()): String =
    SimpleDateFormat("HH:mm:ss", locale).format(Date(at))

@Composable
fun EventRow(
    event: LiveEvent,
    display: EventDisplayConfig = EventDisplayConfig(),
    onClick: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = if (display.compact) 8.dp else 16.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CreatorAvatar(
            event.avatarUrl,
            event.nickname.ifEmpty { event.user },
            if (display.compact) 36.dp else 44.dp,
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (display.showBadge)
                    Icon(
                        event.category.icon(),
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                Text(
                    event.nickname.ifEmpty {
                        event.user.ifEmpty { stringResource(R.string.category_room) }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Emote artwork is always drawn from the event's own metadata. Inline emotes keep the
            // original comment above them; a standalone emote event has no comment to show, so its
            // count falls away once real artwork is available.
            val hasArtwork = event.emotes.any { !it.imageUrl.isNullOrBlank() }
            if (event.category == LiveEvent.Category.EMOTE && hasArtwork) {
                EmoteStrip(event.emotes)
            } else {
                Text(
                    event.summary(),
                    maxLines = if (display.singleLine) 1 else 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hasArtwork) {
                    Spacer(Modifier.height(4.dp))
                    EmoteStrip(event.emotes)
                }
            }
        }
        if (event.giftImageUrl.isNotEmpty())
            AsyncImage(event.giftImageUrl, null, Modifier.size(32.dp))
        if (display.showTime)
            Text(
                localizedEventTime(event.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}
