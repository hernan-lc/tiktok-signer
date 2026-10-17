//! Writes the synthetic event fixtures used by the golden tests.
//!
//! Our real capture (`fixtures/f0/im_fetch.pb`) is the initial HTTP batch and
//! only contains chat among the six events we normalise; gift, like, member,
//! social and room-user only appear on the live WebSocket. Rather than make the
//! test suite depend on an active room, we encode those five from the v3 schema
//! here. They use the same field numbers real traffic does, and Node decodes
//! them independently in `examples/node-connector/golden-fixtures.ts`, so the
//! wire format is checked in both directions.
//!
//! `chat.pb` is a genuine capture and is never overwritten.
//!
//! Given a capture as a second argument it also writes `batch.pb`: the real
//! `ProtoMessageFetchResult` with its session-bearing fields redacted, so a
//! whole-batch fixture can be committed. See [`redact_batch`].
//!
//! ```sh
//! cargo run -p ttl-live-events --example make-fixtures -- \
//!     fixtures/events fixtures/f0/im_fetch.pb
//! ```

use std::path::PathBuf;
use std::{env, fs};

use prost::Message;
use ttl_live_proto::messages::{
    WebcastChatMessage, WebcastEmoteChatMessage, WebcastGiftMessage, WebcastLikeMessage,
    WebcastMemberMessage, WebcastRoomUserSeqMessage, WebcastSocialMessage,
};
use ttl_live_proto::webcast::model::base::emoji::{EmoteModel, EmoteWithIndex};
use ttl_live_proto::webcast::model::base::{user::User, ImageModel};
use ttl_live_proto::webcast::model::Gift;
use ttl_live_proto::ProtoMessageFetchResult;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let out_dir = PathBuf::from(
        args.next()
            .ok_or("usage: make-fixtures <fixtures/events> [capture.pb]")?,
    );
    fs::create_dir_all(&out_dir)?;

    let mut files: Vec<(&str, Vec<u8>)> = vec![
        ("gift.pb", gift().encode_to_vec()),
        ("like.pb", like().encode_to_vec()),
        ("member.pb", member().encode_to_vec()),
        ("social.pb", social().encode_to_vec()),
        ("share.pb", share().encode_to_vec()),
        ("social-unknown.pb", social_unknown().encode_to_vec()),
        ("room-user.pb", room_user().encode_to_vec()),
        ("chat-inline-emote.pb", chat_inline_emote().encode_to_vec()),
        (
            "chat-multiple-emotes.pb",
            chat_multiple_emotes().encode_to_vec(),
        ),
        (
            "chat-unicode-emote.pb",
            chat_unicode_emote().encode_to_vec(),
        ),
        (
            "chat-invalid-emote.pb",
            chat_invalid_emote().encode_to_vec(),
        ),
        ("emote-chat.pb", emote_chat().encode_to_vec()),
    ];

    if let Some(capture) = args.next() {
        let batch = redact_batch(&fs::read(capture)?)?;
        files.push(("batch.pb", batch.encode_to_vec()));
    }

    for (name, bytes) in files {
        fs::write(out_dir.join(name), &bytes)?;
        println!("{name}  ({} bytes)", bytes.len());
    }
    Ok(())
}

/// Strips the session-bearing fields from a captured batch so it can be
/// committed.
///
/// `cursor`, `internal_ext` and `route_params` (which carries `wrss`/`imprp`)
/// are what the client feeds back into the signed WebSocket URI — they identify
/// a session and must not land in the repository. They are replaced with
/// placeholders rather than cleared, so tests still exercise non-empty string
/// passthrough.
///
/// The `messages` themselves are left untouched: they are the point of the
/// fixture, and they carry only what was already public in the room's chat.
fn redact_batch(payload: &[u8]) -> Result<ProtoMessageFetchResult, prost::DecodeError> {
    let mut batch = ttl_live_proto::decode_event_batch(payload)?;
    batch.cursor = "redacted-cursor".to_owned();
    batch.internal_ext = "redacted-internal-ext".to_owned();
    batch.route_params = batch
        .route_params
        .into_keys()
        .map(|key| (key, "redacted".to_owned()))
        .collect();
    Ok(batch)
}

/// Shared identity, so every fixture normalises to the same user block.
fn user() -> User {
    User {
        id: 6_820_914_516_086_834,
        nickname: "Golden Tester".to_owned(),
        display_id: "golden.tester".to_owned(),
        sec_uid: "MS4wLjABAAAAgolden".to_owned(),
        ..Default::default()
    }
}

fn gift() -> WebcastGiftMessage {
    WebcastGiftMessage {
        gift_id: 5655,
        repeat_count: 7,
        combo_count: 3,
        group_id: 1_739_382_910_000,
        repeat_end: 1,
        user: Some(user()),
        gift: Some(Gift {
            id: 5655,
            name: "Rose".to_owned(),
            diamond_count: 1,
            ..Default::default()
        }),
        ..Default::default()
    }
}

fn like() -> WebcastLikeMessage {
    WebcastLikeMessage {
        count: 12,
        total: 4821,
        user: Some(user()),
        ..Default::default()
    }
}

fn member() -> WebcastMemberMessage {
    WebcastMemberMessage {
        member_count: 1284,
        action: 1,
        user: Some(user()),
        ..Default::default()
    }
}

/// A confirmed follow (`action == 1`) that also carries the room's cumulative
/// `follow_count`/`share_count` and a couple of the auxiliary social fields, so
/// the golden comparison pins both the discriminator and the retained evidence.
fn social() -> WebcastSocialMessage {
    WebcastSocialMessage {
        action: 1,
        follow_count: 9312,
        share_count: 4,
        follow_type: 2,
        target_user_id: 6_820_914_516_086_834,
        user: Some(user()),
        ..Default::default()
    }
}

/// A confirmed share (`action == 3`).
///
/// The `follow_count` is deliberately non-zero: it is a cumulative room total
/// that TikTok keeps sending on share messages, and classifying by "the first
/// positive counter" would label this a follow. The golden tests lock that in.
fn share() -> WebcastSocialMessage {
    WebcastSocialMessage {
        action: 3,
        follow_count: 9312,
        share_count: 5,
        share_type: 1,
        share_target: "https://example.invalid/live".to_owned(),
        share_display_style: 1,
        scene: "share_scene".to_owned(),
        user: Some(user()),
        ..Default::default()
    }
}

/// A social message with an `action` we have no confirmed rule for. It must
/// stay unclassified (never counted as a follow or a share) while keeping its
/// raw code.
fn social_unknown() -> WebcastSocialMessage {
    WebcastSocialMessage {
        action: 97,
        follow_count: 9312,
        user: Some(user()),
        ..Default::default()
    }
}

fn room_user() -> WebcastRoomUserSeqMessage {
    WebcastRoomUserSeqMessage {
        total: 1284,
        popularity: 2048,
        total_user: 55391,
        anonymous: 17,
        ..Default::default()
    }
}

/// An emote artwork block with a single URL, as the room sends it.
fn emote_image(url: &str) -> ImageModel {
    ImageModel {
        url_list: vec![url.to_owned()],
        ..Default::default()
    }
}

/// A named emote package. `emote_type` is the raw `EmoteType` enum value;
/// `0` (`EMOTE_TYPE_NORMAL`) is omitted from the normalized `emoteType`.
fn emote(id: &str, url: &str, emote_type: i32, package_id: &str) -> EmoteModel {
    EmoteModel {
        emote_id: id.to_owned(),
        image: Some(emote_image(url)),
        emote_type,
        package_id: package_id.to_owned(),
        ..Default::default()
    }
}

/// A single inline emote in a text comment.
///
/// `index` is the raw `EmoteWithIndex.index` (here the UTF-8 byte offset of the
/// `[` in `"Hello [laughcry]"`); it is preserved without conversion.
fn chat_inline_emote() -> WebcastChatMessage {
    WebcastChatMessage {
        content: "Hello [laughcry]".to_owned(),
        user: Some(user()),
        emotes: vec![EmoteWithIndex {
            index: 6,
            emote: Some(emote(
                "7098",
                "https://p16-webcast.tiktokcdn.com/img/emote/laughcry.webp",
                1,
                "7100000000000000001",
            )),
        }],
        ..Default::default()
    }
}

/// Two inline emotes in one comment, so order and per-emote metadata are pinned.
fn chat_multiple_emotes() -> WebcastChatMessage {
    WebcastChatMessage {
        content: "Hola [happy] amigo [wow]".to_owned(),
        user: Some(user()),
        emotes: vec![
            EmoteWithIndex {
                index: 5,
                emote: Some(emote(
                    "7099",
                    "https://p16-webcast.tiktokcdn.com/img/emote/happy.webp",
                    0,
                    "",
                )),
            },
            EmoteWithIndex {
                index: 19,
                emote: Some(emote(
                    "7100",
                    "https://p16-webcast.tiktokcdn.com/img/emote/wow.webp",
                    0,
                    "",
                )),
            },
        ],
        ..Default::default()
    }
}

/// A CJK comment with an emote, so the normaliser is proven not to touch the
/// original (multibyte) text while it extracts the emote.
fn chat_unicode_emote() -> WebcastChatMessage {
    WebcastChatMessage {
        content: "こんにちは [laughcry]".to_owned(),
        user: Some(user()),
        emotes: vec![EmoteWithIndex {
            index: 16,
            emote: Some(emote(
                "7098",
                "https://p16-webcast.tiktokcdn.com/img/emote/laughcry.webp",
                0,
                "",
            )),
        }],
        ..Default::default()
    }
}

/// Malformed metadata must degrade per-emote, never fail the message:
///
/// * the first emote keeps its id but loses its unsafe `javascript:` URL;
/// * the second carries neither an id nor an image and is dropped entirely.
fn chat_invalid_emote() -> WebcastChatMessage {
    WebcastChatMessage {
        content: "Bad [emote]".to_owned(),
        user: Some(user()),
        emotes: vec![
            EmoteWithIndex {
                index: 4,
                emote: Some(EmoteModel {
                    emote_id: "7091".to_owned(),
                    image: Some(emote_image("javascript:alert(1)")),
                    ..Default::default()
                }),
            },
            EmoteWithIndex {
                index: 10,
                emote: Some(EmoteModel::default()),
            },
        ],
        ..Default::default()
    }
}

/// A standalone `WebcastEmoteChatMessage`: two emotes and no text comment.
fn emote_chat() -> WebcastEmoteChatMessage {
    WebcastEmoteChatMessage {
        user: Some(user()),
        emote_list: vec![
            emote(
                "7099",
                "https://p16-webcast.tiktokcdn.com/img/emote/happy.webp",
                0,
                "7100000000000000002",
            ),
            emote(
                "7100",
                "https://p16-webcast.tiktokcdn.com/img/emote/wow.webp",
                0,
                "",
            ),
        ],
        ..Default::default()
    }
}
