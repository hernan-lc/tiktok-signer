//! Emote normalization: inline `WebcastChatMessage.emotes`, standalone
//! `WebcastEmoteChatMessage`, and the degradation rules that keep one malformed
//! emote from ever taking down a chat message.
//!
//! Most emote fixtures are synthetic (`examples/make-fixtures.rs`).
//! `captured-inline-emote.pb` is reduced/sanitized from real traffic. Other
//! fixtures use explicit synthetic boundary cases; additional cases are encoded
//! inline here so a rule can be pinned without a new committed file.

use std::path::{Path, PathBuf};

use prost::Message;
use ttl_live_events::{
    decode_batch, decode_batch_with_mode, decode_event, method, DecodeMode, LiveEmote, LiveEvent,
    MAX_EMOTES_PER_EVENT, MAX_EMOTE_ID_LENGTH, MAX_EMOTE_URL_LENGTH,
};
use ttl_live_proto::messages::{WebcastChatMessage, WebcastEmoteChatMessage};
use ttl_live_proto::webcast::model::base::emoji::{EmoteModel, EmoteWithIndex};
use ttl_live_proto::webcast::model::base::user::User;
use ttl_live_proto::webcast::model::base::ImageModel;
use ttl_sign_core::proto::Writer;

fn fixtures() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/events")
}

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(fixtures().join(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn user() -> User {
    User {
        id: 42,
        nickname: "Ay".to_owned(),
        display_id: "ay".to_owned(),
        ..Default::default()
    }
}

fn image(url: &str) -> ImageModel {
    ImageModel {
        url_list: vec![url.to_owned()],
        ..Default::default()
    }
}

fn emote(id: &str, url: &str) -> EmoteModel {
    EmoteModel {
        emote_id: id.to_owned(),
        image: Some(image(url)),
        ..Default::default()
    }
}

fn chat(content: &str, emotes: Vec<EmoteWithIndex>) -> Vec<u8> {
    WebcastChatMessage {
        content: content.to_owned(),
        user: Some(user()),
        emotes,
        ..Default::default()
    }
    .encode_to_vec()
}

#[test]
fn plain_chat_has_no_emotes_and_omits_the_key() {
    let event = decode_event(method::CHAT, &chat("hola", vec![]));
    let LiveEvent::Chat(chat) = event else {
        panic!("expected chat")
    };
    assert_eq!(chat.comment, "hola");
    assert!(chat.emotes.is_empty());

    // The text-only JSON contract is unchanged: an empty list is not emitted.
    let json = serde_json::to_string(&ttl_live_events::LiveEvent::Chat(chat)).unwrap();
    assert!(!json.contains("emotes"), "{json}");
}

#[test]
fn one_inline_emote_carries_its_metadata() {
    let event = decode_event(method::CHAT, &fixture("chat-inline-emote.pb"));
    let LiveEvent::Chat(chat) = event else {
        panic!("expected chat")
    };
    assert_eq!(chat.comment, "Hello [laughcry]", "original text preserved");
    assert_eq!(chat.emotes.len(), 1);
    let emote = &chat.emotes[0];
    assert_eq!(emote.id, "7098");
    assert_eq!(
        emote.image_url.as_deref(),
        Some("https://p16-webcast.tiktokcdn.com/img/emote/laughcry.webp")
    );
    assert_eq!(
        emote.index,
        Some(6),
        "raw index preserved without conversion"
    );
    assert_eq!(emote.emote_type, Some(1));
    assert_eq!(emote.package_id.as_deref(), Some("7100000000000000001"));
    // No v3 field is verified to be the shortcode, so it is never fabricated.
    assert_eq!(emote.shortcode, None);
}

#[test]
fn multiple_inline_emotes_keep_wire_order() {
    let event = decode_event(method::CHAT, &fixture("chat-multiple-emotes.pb"));
    let LiveEvent::Chat(chat) = event else {
        panic!("expected chat")
    };
    let ids: Vec<&str> = chat.emotes.iter().map(|e| e.id.as_str()).collect();
    assert_eq!(ids, ["7099", "7100"]);
    assert_eq!(chat.emotes[0].index, Some(5));
    assert_eq!(chat.emotes[1].index, Some(19));
    // `emote_type == 0` is indistinguishable from absent, so it is omitted.
    assert_eq!(chat.emotes[0].emote_type, None);
}

#[test]
fn unicode_comment_is_untouched() {
    let event = decode_event(method::CHAT, &fixture("chat-unicode-emote.pb"));
    let LiveEvent::Chat(chat) = event else {
        panic!("expected chat")
    };
    assert_eq!(chat.comment, "こんにちは [laughcry]");
    assert_eq!(chat.emotes.len(), 1);
}

#[test]
fn a_missing_image_url_keeps_the_emote() {
    let payload = chat(
        "[x]",
        vec![EmoteWithIndex {
            index: 0,
            emote: Some(EmoteModel {
                emote_id: "123".to_owned(),
                ..Default::default()
            }),
        }],
    );
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(chat.emotes.len(), 1);
    assert_eq!(chat.emotes[0].id, "123");
    assert_eq!(chat.emotes[0].image_url, None);
}

#[test]
fn an_unsafe_image_url_is_dropped_but_the_emote_is_kept() {
    for url in [
        "javascript:alert(1)",
        "data:image/png;base64,AAAA",
        "file:///etc/passwd",
        "https://",
        "https:///no-host",
        "ftp://example.com/x.webp",
    ] {
        let payload = chat(
            "[x]",
            vec![EmoteWithIndex {
                index: 0,
                emote: Some(emote("77", url)),
            }],
        );
        let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
            panic!("expected chat")
        };
        assert_eq!(chat.emotes.len(), 1, "{url}");
        assert_eq!(chat.emotes[0].id, "77", "{url}");
        assert_eq!(chat.emotes[0].image_url, None, "{url} must not survive");
    }
}

#[test]
fn an_http_url_is_accepted_and_a_second_valid_url_is_used() {
    let payload = chat(
        "[x]",
        vec![EmoteWithIndex {
            index: 0,
            emote: Some(EmoteModel {
                emote_id: "5".to_owned(),
                image: Some(ImageModel {
                    url_list: vec![
                        "javascript:alert(1)".to_owned(),
                        "http://cdn.example/ok.webp".to_owned(),
                    ],
                    ..Default::default()
                }),
                ..Default::default()
            }),
        }],
    );
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(
        chat.emotes[0].image_url.as_deref(),
        Some("http://cdn.example/ok.webp"),
        "an invalid first URL falls through to the next"
    );
}

#[test]
fn url_validation_accepts_real_hosts_and_rejects_obfuscation() {
    // An accepted URL keeps the emote's artwork; a rejected one drops only the
    // URL. The emote id is irrelevant to the rule under test.
    let keep = |url: &str| {
        let payload = chat(
            "[x]",
            vec![EmoteWithIndex {
                index: 0,
                emote: Some(emote("7", url)),
            }],
        );
        let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
            panic!("expected chat")
        };
        chat.emotes[0].image_url.is_some()
    };

    for url in [
        "http://cdn.example:8080/img/x.webp",
        "https://[::1]:8443/img/x.webp",
        "https://xn--r8jz45g.xn--zckzah/x.webp",
        // Unicode hostnames are decoded through IDNA by the parser.
        "https://例え.テスト/x.webp",
    ] {
        assert!(keep(url), "{url} should be accepted");
    }

    for url in [
        // Credentials must never be smuggled through an image URL.
        "https://user@cdn.example/x.webp",
        "https://user:pass@cdn.example/x.webp",
        // An out-of-range port is not a usable authority.
        "http://cdn.example:99999/x.webp",
        // An empty authority is never repaired into a host.
        "https:///no-host",
        "https://",
        // Scheme-only ("http:example.com") is not the absolute `://` form.
        "http:cdn.example/x.webp",
        // Embedded control characters are rejected before parsing.
        "https://cdn.example/\u{0007}x.webp",
    ] {
        assert!(!keep(url), "{url} should be rejected");
    }
}

#[test]
fn an_emote_with_neither_id_nor_image_is_dropped() {
    let payload = fixture("chat-invalid-emote.pb");
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(chat.comment, "Bad [emote]");
    assert_eq!(chat.emotes.len(), 1, "only the identifiable emote survives");
    assert_eq!(chat.emotes[0].id, "7091");
    assert_eq!(chat.emotes[0].image_url, None, "javascript: URL rejected");
}

#[test]
fn a_negative_index_is_preserved_verbatim() {
    let payload = chat(
        "[x]",
        vec![EmoteWithIndex {
            index: -1,
            emote: Some(emote("9", "https://cdn.example/x.webp")),
        }],
    );
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(chat.emotes[0].index, Some(-1));
}

#[test]
fn oversized_metadata_is_bounded_not_fatal() {
    // An id past the cap drops the emote; a URL past the cap keeps the emote.
    let long_id = "a".repeat(MAX_EMOTE_ID_LENGTH + 1);
    let long_url = format!("https://cdn.example/{}", "u".repeat(MAX_EMOTE_URL_LENGTH));
    let payload = chat(
        "[a][b]",
        vec![
            EmoteWithIndex {
                index: 0,
                emote: Some(emote(&long_id, "https://cdn.example/ok.webp")),
            },
            EmoteWithIndex {
                index: 3,
                emote: Some(emote("ok", &long_url)),
            },
        ],
    );
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(chat.emotes.len(), 1);
    assert_eq!(chat.emotes[0].id, "ok");
    assert_eq!(chat.emotes[0].image_url, None);
}

#[test]
fn the_emote_count_is_capped() {
    let emotes = (0..MAX_EMOTES_PER_EVENT + 5)
        .map(|i| EmoteWithIndex {
            index: i as i32,
            emote: Some(emote(&format!("{i}"), "https://cdn.example/x.webp")),
        })
        .collect();
    let payload = chat("[flood]", emotes);
    let LiveEvent::Chat(chat) = decode_event(method::CHAT, &payload) else {
        panic!("expected chat")
    };
    assert_eq!(chat.emotes.len(), MAX_EMOTES_PER_EVENT);
}

#[test]
fn a_standalone_emote_event_is_its_own_type() {
    let event = decode_event(method::EMOTE_CHAT, &fixture("emote-chat.pb"));
    assert_eq!(event.method(), method::EMOTE_CHAT);
    let LiveEvent::EmoteChat(emote_chat) = event else {
        panic!("expected emote chat")
    };
    assert_eq!(emote_chat.user.unique_id, "golden.tester");
    assert_eq!(emote_chat.emotes.len(), 2);
    let ids: Vec<&str> = emote_chat.emotes.iter().map(|e| e.id.as_str()).collect();
    assert_eq!(ids, ["7099", "7100"]);
    assert_eq!(
        emote_chat.emotes[0].package_id.as_deref(),
        Some("7100000000000000002")
    );
    assert!(
        emote_chat.emotes.iter().all(|e| e.index.is_none()),
        "standalone emotes carry no index"
    );
}

#[test]
fn an_emote_only_message_with_no_text_stays_valid() {
    // No `content` at all: an empty comment must not drop the event.
    let payload = WebcastEmoteChatMessage {
        user: Some(user()),
        emote_list: vec![emote("7098", "https://cdn.example/x.webp")],
        ..Default::default()
    }
    .encode_to_vec();
    let event = decode_event(method::EMOTE_CHAT, &payload);
    assert!(
        event.user().is_some(),
        "an emote-only event still has a user"
    );
    let LiveEvent::EmoteChat(emote_chat) = event else {
        panic!("expected emote chat")
    };
    assert_eq!(emote_chat.emotes.len(), 1);
}

#[test]
fn malformed_payloads_degrade_to_unknown() {
    for payload in [vec![0xff, 0xff, 0xff, 0xff], vec![0x0a, 0xff]] {
        let chat = decode_event(method::CHAT, &payload);
        let emote_chat = decode_event(method::EMOTE_CHAT, &payload);
        assert!(chat.is_unknown(), "{payload:?}");
        assert!(emote_chat.is_unknown(), "{payload:?}");
    }
}

#[test]
fn every_decoding_mode_normalises_emote_chat() {
    // A batch carrying one emote-only message, exercised through each mode.
    let inner = WebcastEmoteChatMessage {
        user: Some(user()),
        emote_list: vec![emote("7098", "https://cdn.example/x.webp")],
        ..Default::default()
    }
    .encode_to_vec();
    let mut message = Writer::new();
    message
        .str_field(1, method::EMOTE_CHAT)
        .bytes_field(2, &inner)
        .u64_field(3, 7)
        .bool_field(6, true);
    let mut batch = Writer::new();
    batch.bytes_field(1, &message.finish());
    let encoded = batch.finish();

    let legacy = decode_batch(&encoded).unwrap();
    assert!(matches!(legacy.events[0].event, LiveEvent::EmoteChat(_)));

    for mode in [
        DecodeMode::Normalized,
        DecodeMode::Full,
        DecodeMode::Schema,
        DecodeMode::Raw,
    ] {
        let batch = decode_batch_with_mode(&encoded, mode).unwrap();
        assert_eq!(batch.events[0].msg_id, 7);
        assert!(batch.events[0].is_history);
        if matches!(mode, DecodeMode::Normalized | DecodeMode::Full) {
            assert!(
                matches!(batch.events[0].event, Some(LiveEvent::EmoteChat(_))),
                "{mode:?}"
            );
        }
    }
}

#[test]
fn replaying_the_same_payload_is_deterministic() {
    // Reconnect replay: the same bytes must produce the same normalized event so
    // downstream dedup keeps working.
    let payload = fixture("emote-chat.pb");
    let first = decode_event(method::EMOTE_CHAT, &payload);
    let second = decode_event(method::EMOTE_CHAT, &payload);
    assert_eq!(first, second);
    assert!(!first.is_unknown());
}

#[test]
fn serialization_uses_the_public_camel_case_shape() {
    let event = decode_event(method::CHAT, &fixture("chat-inline-emote.pb"));
    let value = serde_json::to_value(&event).unwrap();
    assert_eq!(value["type"], "chat");
    let emote = &value["emotes"][0];
    assert_eq!(emote["id"], "7098");
    assert_eq!(emote["index"], 6);
    assert_eq!(emote["emoteType"], 1);
    assert_eq!(emote["packageId"], "7100000000000000001");
    assert!(emote["imageUrl"].is_string());
    // `shortcode` is absent rather than invented.
    assert!(emote.get("shortcode").is_none());
}

#[test]
fn a_hand_built_emote_serializes_without_optional_fields() {
    let event = LiveEvent::EmoteChat(ttl_live_events::EmoteChatEvent {
        user: ttl_live_events::EventUser::default(),
        emotes: vec![LiveEmote {
            id: "1".to_owned(),
            ..Default::default()
        }],
    });
    let value = serde_json::to_value(&event).unwrap();
    assert_eq!(value["type"], "emote_chat");
    assert_eq!(value["emotes"][0], serde_json::json!({ "id": "1" }));
}

#[test]
fn an_unmodelled_method_still_falls_back_to_unknown() {
    let event = decode_event("WebcastEmoteSomethingElse", &[8, 1]);
    assert!(event.is_unknown());
    assert_eq!(event.method(), "WebcastEmoteSomethingElse");
}

#[test]
fn sanitized_real_inline_emote_matches_the_node_oracle_and_wire_presence() {
    let payload = fixture("captured-inline-emote.pb");
    let envelope = ttl_live_events::decode_event_envelope(method::CHAT, 1, false, &payload);
    let expected: serde_json::Value = serde_json::from_slice(
        &std::fs::read(fixtures().join("expected/captured-inline-emote.json")).unwrap(),
    )
    .unwrap();
    let LiveEvent::Chat(chat) = &envelope.event else {
        panic!("expected captured chat");
    };
    assert_eq!(chat.comment, expected["comment"].as_str().unwrap());
    assert_eq!(
        serde_json::to_value(&chat.emotes).unwrap(),
        expected["emotes"]
    );
    assert_eq!(
        envelope.wire_emote_entries(),
        Some((chat.emotes.len(), true))
    );
    assert_eq!(chat.user.id, 1);
    assert_eq!(chat.user.nickname, "fixture");
}
