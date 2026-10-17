package dev.nglmercer.tiktools.core.model

import java.net.IDN
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/**
 * A normalized emote from the core's JSON. Mirrors the public Rust/TypeScript shape exactly;
 * optional fields are `null` when the wire did not carry them rather than guessed.
 */
data class LiveEmote(
    val id: String,
    val shortcode: String? = null,
    val imageUrl: String? = null,
    val index: Int? = null,
    val emoteType: Int? = null,
    val packageId: String? = null,
)

/**
 * One normalized live event, parsed from the Rust core's JSON push (`{"type": …, …}`). Small and
 * typed so the reader, points, actions, and (future) TTS all share one interpretation of the
 * stream.
 *
 * Unknown shapes degrade to [Category.UNKNOWN] with the raw payload kept — TikTok adds methods
 * freely, and nothing may crash the reader.
 */
data class LiveEvent(
    val category: Category,
    /** Stable handle without the `@`, empty when the event has no user. */
    val user: String,
    val nickname: String,
    /** Chat text, gift name, or empty. */
    val text: String,
    /** Gift/like repeat count, viewer total, member action; 0 when absent. */
    val count: Long,
    /** Gift diamond value; 0 when absent. */
    val diamonds: Long,
    /** Sender avatar URL (`user.avatar_url`); empty when absent. */
    val avatarUrl: String = "",
    /** Gift artwork URL; empty when absent or not a gift. */
    val giftImageUrl: String = "",
    /** Epoch millis when the event landed on device. */
    val at: Long = System.currentTimeMillis(),
    /** The original JSON, kept for actions templates and debugging. */
    val raw: String = "",
    /** Structured emotes carried by this event; empty for most events. */
    val emotes: List<LiveEmote> = emptyList(),
) {
    enum class Category {
        CHAT,
        /** A standalone `WebcastEmoteChatMessage`: emotes with no accompanying text. */
        EMOTE,
        GIFT,
        LIKE,
        FOLLOW,
        SHARE,
        /** A social message whose `action` we have no confirmed rule for. */
        SOCIAL,
        JOIN,
        MEMBER,
        ROOM,
        UNKNOWN,
    }

    /**
     * Shortcodes confirmed by this event's own metadata. Always empty today (the schema has no
     * verified shortcode field), but the sanitizer prefers them over the provisional catalog when a
     * future build populates them.
     */
    fun shortcodes(): Set<String> = emotes.mapNotNull { it.shortcode }.toSet()
}

/**
 * Confirmed `WebcastSocialMessage.action` values.
 *
 * A follow and a share arrive on the same message type and are told apart by
 * this field alone. `follow_count` and `share_count` are cumulative room totals
 * — a share message still carries a non-zero `follow_count` — so classifying by
 * "the first positive counter" labels every share a follow. That was the bug
 * here; see `ttl_live_events::social` for the canonical table.
 */
object SocialAction {
    const val FOLLOW = 1L
    const val SHARE = 3L
}

/**
 * Pure parser: event JSON → [LiveEvent]. Total — garbage in gives an [LiveEvent.Category.UNKNOWN]
 * event, never an exception.
 */
object EventParser {

    fun parse(json: String, at: Long = System.currentTimeMillis()): LiveEvent {
        val value =
            try {
                JSONObject(json)
            } catch (e: Exception) {
                return LiveEvent(LiveEvent.Category.UNKNOWN, "", "", "", 0, 0, at = at, raw = json)
            }
        val type = value.optString("type", "?")
        val userObj = value.optJSONObject("user")
        val user = userObj?.optString("unique_id", "").orEmpty()
        val nickname = userObj?.optString("nickname", "").orEmpty()
        val emotes = parseEmotes(value.optJSONArray("emotes"))
        val base =
            LiveEvent(
                category = LiveEvent.Category.UNKNOWN,
                user = user,
                nickname = nickname,
                text = "",
                count = 0,
                diamonds = 0,
                // optString maps a JSON null (serde's None) to the default, so
                // older native builds without these keys parse identically.
                avatarUrl = userObj?.optString("avatar_url", "").orEmpty(),
                giftImageUrl = value.optString("gift_image_url", ""),
                at = at,
                raw = json,
                emotes = emotes,
            )
        return when (type) {
            "chat" ->
                base.copy(
                    category = LiveEvent.Category.CHAT,
                    text = value.optString("comment", "").take(500),
                )
            // A standalone emote message has no text comment; the emote count is
            // the only thing the reader can show, and speech stays silent for it.
            "emote_chat" ->
                base.copy(
                    category = LiveEvent.Category.EMOTE,
                    count = emotes.size.toLong(),
                )
            "gift" ->
                base.copy(
                    category = LiveEvent.Category.GIFT,
                    text = value.optString("gift_name", "?"),
                    count = value.optLong("repeat_count", 0),
                    diamonds = value.optLong("diamond_count", 0),
                )
            "like" ->
                base.copy(category = LiveEvent.Category.LIKE, count = value.optLong("count", 0))
            // Action 1 is a join in every room observed; anything else stays a
            // generic member event, matching the core's own caution.
            "member" ->
                if (value.optLong("action", -1) == 1L) {
                    base.copy(category = LiveEvent.Category.JOIN)
                } else {
                    base.copy(
                        category = LiveEvent.Category.MEMBER,
                        count = value.optLong("action", -1),
                    )
                }
            // A follow and a share share one message type; `action` is the
            // discriminator. The counters are cumulative room totals and must
            // never decide the category — a share still carries the room's
            // follower total, so the old "first positive counter" test turned
            // every share into a follow.
            "social" ->
                when (value.optLong("action", 0)) {
                    SocialAction.FOLLOW -> base.copy(category = LiveEvent.Category.FOLLOW)
                    SocialAction.SHARE -> base.copy(category = LiveEvent.Category.SHARE)
                    else ->
                        base.copy(
                            category = LiveEvent.Category.SOCIAL,
                            count = value.optLong("action", 0),
                        )
                }
            "room_user" ->
                base.copy(category = LiveEvent.Category.ROOM, count = value.optLong("total", 0))
            else ->
                base.copy(
                    text = value.optString("method", type),
                    count = value.optLong("bytes", -1),
                )
        }
    }

    /**
     * Parse the normalized `emotes` array. Tolerant by construction: a malformed entry is skipped,
     * an emote with neither an id nor artwork (nothing to show) is dropped, and the list is capped,
     * matching the Rust and TypeScript decoders. The count, id-length and URL rules are the same
     * bounds the core enforces, so a hand-crafted or intermediate payload cannot bypass them.
     */
    private fun parseEmotes(array: JSONArray?): List<LiveEmote> {
        if (array == null || array.length() == 0) return emptyList()
        val emotes = ArrayList<LiveEmote>(minOf(array.length(), MAX_EMOTES_PER_EVENT))
        for (index in 0 until array.length()) {
            if (emotes.size >= MAX_EMOTES_PER_EVENT) break
            val entry = array.optJSONObject(index) ?: continue
            val id = entry.optString("id", "")
            // An over-long id is dropped rather than truncated into a colliding one.
            if (id.toByteArray(Charsets.UTF_8).size > MAX_EMOTE_ID_LENGTH) continue
            val rawImageUrl = entry.optString("imageUrl", "")
            val imageUrl = rawImageUrl.takeIf { isSafeImageUrl(it) }
            if (id.isEmpty() && imageUrl == null) continue
            emotes.add(
                LiveEmote(
                    id = id,
                    shortcode = entry.optString("shortcode", "").ifEmpty { null },
                    imageUrl = imageUrl,
                    index = if (entry.has("index")) entry.optInt("index") else null,
                    emoteType = if (entry.has("emoteType")) entry.optInt("emoteType") else null,
                    packageId = entry.optString("packageId", "").ifEmpty { null },
                )
            )
        }
        return emotes
    }

    /**
     * Whether an artwork URL is an absolute `http(s)` URL with a real host, no credentials and no
     * control characters, within the byte bound. Mirrors the Rust `url`-crate rule and the
     * TypeScript `URL` rule so a URL the core accepted (or a future one it rejects) is treated the
     * same on device.
     */
    private fun isSafeImageUrl(url: String): Boolean {
        if (url.isEmpty() || url.toByteArray(Charsets.UTF_8).size > MAX_EMOTE_URL_LENGTH) return false
        for (character in url) {
            val code = character.code
            if (character == ' ' || code < 0x20 || code in 0x7f..0x9f) return false
        }
        val uri =
            try {
                URI(url)
            } catch (_: Exception) {
                return false
            }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val authority = uri.rawAuthority ?: return false
        if (authority.isEmpty() || authority.startsWith('/')) return false
        if (uri.port > 65535) return false
        val host = uri.host
        if (host != null) return host.isNotEmpty()
        // A Unicode host: `URI` leaves `host` null, so confirm IDNA can encode it.
        val plainHost = authority.substringBeforeLast(':').removeSurrounding("[", "]")
        return runCatching { IDN.toASCII(plainHost) }.map { it.isNotEmpty() }.getOrDefault(false)
    }

    /** Upper bound on the emotes carried by one event; mirrors the core. */
    const val MAX_EMOTES_PER_EVENT = 64

    /** Upper bound on an emote identifier in UTF-8 bytes; mirrors the core. */
    const val MAX_EMOTE_ID_LENGTH = 128

    /** Upper bound on an emote artwork URL in UTF-8 bytes; mirrors the core. */
    const val MAX_EMOTE_URL_LENGTH = 2048
}
