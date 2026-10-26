use ttl_live_events::{
    decode_batch, decode_batch_with_mode, decode_event_envelope, AuditReport, DecodeMode,
    NormalizationStatus as Status,
};
use ttl_sign_core::proto::Writer;

#[test]
fn telemetry_counts_inline_and_standalone_emotes_without_retaining_content() {
    let mut report = AuditReport::default();
    report.observe(&ttl_live_events::EventBatch {
        events: vec![
            decode_event_envelope(
                "WebcastChatMessage",
                1,
                false,
                include_bytes!("../../../fixtures/events/chat-inline-emote.pb"),
            ),
            decode_event_envelope(
                "WebcastEmoteChatMessage",
                2,
                false,
                include_bytes!("../../../fixtures/events/emote-chat.pb"),
            ),
        ],
        ..Default::default()
    });
    assert_eq!(report.methods["WebcastChatMessage"].emotes, 1);
    assert_eq!(report.methods["WebcastEmoteChatMessage"].emotes, 2);
    assert_eq!(report.methods["WebcastChatMessage"].wire_emote_entries, 1);
    assert_eq!(
        report.methods["WebcastEmoteChatMessage"].wire_emote_entries,
        2
    );
    assert_eq!(
        report.methods["WebcastEmoteChatMessage"].events_with_emotes,
        1
    );
}

#[test]
fn real_social_negative_target_sentinel_is_not_clamped_into_an_absent_target() {
    // Minimal reconstruction of a captured -1 target, without participant data.
    let payload = Writer::new()
        .u64_field(4, 1)
        .u64_field(14, u64::MAX)
        .clone()
        .finish();
    let decoded = decode_event_envelope("WebcastSocialMessage", 1, false, &payload);
    let ttl_live_events::LiveEvent::Social(event) = &decoded.event else {
        panic!("expected social");
    };
    assert_eq!(event.target_user_id, -1);
    assert!(event
        .classify()
        .evidence
        .contains(&ttl_live_events::SocialEvidence::TargetUserId(-1)));
    let json: serde_json::Value = serde_json::from_str(
        &decoded
            .to_json(ttl_live_events::JsonMode::Normalized)
            .unwrap(),
    )
    .unwrap();
    assert_eq!(json["target_user_id"], "-1");
}

#[test]
fn unknown_enum_values_are_visible_even_when_the_field_number_is_known() {
    let (schema, field) = ttl_live_proto::schemas()
        .iter()
        .find_map(|schema| {
            if !schema.name.rsplit('.').next()?.starts_with("Webcast") {
                return None;
            }
            schema
                .fields
                .iter()
                .find(|field| matches!(field.value_kind, ttl_live_proto::FieldValueKind::Enum(_)))
                .map(|field| (schema, field))
        })
        .expect("a webcast enum descriptor");
    let payload = Writer::new()
        .u64_field(field.number, i32::MAX as u64)
        .clone()
        .finish();
    let decoded =
        decode_event_envelope(schema.name.rsplit('.').next().unwrap(), 1, false, &payload);
    let enums = decoded.diagnostics().unknown_enums;
    assert_eq!(enums.len(), 1);
    assert_eq!(enums[0].path, [field.number]);
    assert_eq!(enums[0].number, i32::MAX);
}

#[test]
fn an_empty_nested_message_at_the_exact_field_boundary_remains_valid() {
    let mut payload = [16, 1].repeat(4095);
    payload.extend_from_slice(&[58, 0]); // field 7 (user), explicitly empty nested message
    let event = decode_event_envelope("WebcastGiftMessage", 1, false, &payload);
    assert_eq!(event.normalization_status, Status::Normalized);
}

#[test]
fn missing_values_are_observable_without_rejecting_legitimate_partial_gifts() {
    let absent = decode_event_envelope("WebcastGiftMessage", 1, false, &[]);
    assert_eq!(absent.normalization_status, Status::Normalized);
    assert!(absent.diagnostics().absent_fields.contains(&"gift_id"));
    let explicit_zero = vec![16, 0]; // Writer omits proto3 defaults; deliberately encode presence.
    let present = decode_event_envelope("WebcastGiftMessage", 1, false, &explicit_zero);
    assert!(!present.diagnostics().absent_fields.contains(&"gift_id"));
    assert!(present.diagnostics().absent_fields.contains(&"gift"));
    assert_eq!(absent.event, present.event);
}

#[test]
fn unsupported_malformed_and_typed_decode_failures_are_distinct() {
    for (method, payload, expected) in [
        (
            "WebcastFutureMessage",
            vec![8, 42],
            Status::UnsupportedMethod,
        ),
        ("WebcastChatMessage", vec![255], Status::MalformedWire),
        // Structurally valid wire, invalid UTF-8 string.
        ("WebcastChatMessage", vec![26, 1, 255], Status::DecodeError),
    ] {
        let event = decode_event_envelope(method, 1, false, &payload);
        assert_eq!(event.normalization_status, expected);
        assert!(event.event.is_unknown());
        assert_eq!(event.raw_payload(), payload);
    }
}

#[test]
fn nested_unknown_paths_preserve_wire_type_and_do_not_hide_valid_chat() {
    let user = Writer::new().u64_field(999, 5).clone().finish();
    let payload = Writer::new()
        .bytes_field(2, &user)
        .str_field(3, "hello")
        .clone()
        .finish();
    let event = decode_event_envelope("WebcastChatMessage", 1, false, &payload);
    assert_eq!(event.normalization_status, Status::Normalized);
    let fields = event.diagnostics().unknown_fields;
    assert_eq!(fields.len(), 1);
    assert_eq!(fields[0].path, [2, 999]);
    assert_eq!(fields[0].wire_type, 0);
}

fn batch(payloads: &[&[u8]]) -> Vec<u8> {
    let mut batch = Writer::new();
    for payload in payloads {
        let message = Writer::new()
            .str_field(1, "WebcastGiftMessage")
            .bytes_field(2, payload)
            .clone()
            .finish();
        batch.bytes_field(1, &message);
    }
    batch.finish()
}

#[test]
fn independent_and_shared_limits_have_distinct_causes_in_both_apis() {
    let bounded = [16, 1].repeat(4096);
    let excessive = [16, 1].repeat(4097);
    assert_eq!(
        decode_event_envelope("WebcastGiftMessage", 0, false, &excessive).normalization_status,
        Status::FieldLimit
    );
    let encoded = batch(&vec![bounded.as_slice(); 17]);
    let original = decode_batch(&encoded).unwrap();
    assert_eq!(
        original.events[16].normalization_status,
        Status::BatchFieldLimit
    );
    for mode in [DecodeMode::Full, DecodeMode::Normalized] {
        let selected = decode_batch_with_mode(&encoded, mode).unwrap();
        for (event, original) in selected.events.iter().zip(&original.events) {
            assert_eq!(event.normalization_status, original.normalization_status);
        }
    }
}

#[test]
fn raw_and_schema_modes_still_report_preflight_failures() {
    let encoded = batch(&[&[255]]);
    for mode in [DecodeMode::Raw, DecodeMode::Schema] {
        let selected = decode_batch_with_mode(&encoded, mode).unwrap();
        assert_eq!(
            selected.events[0].normalization_status,
            Status::MalformedWire
        );
        assert!(selected.events[0].truncated);
        assert!(selected.events[0].event.is_none());
    }
}

#[test]
fn real_capture_reports_diagnostics_and_never_claims_absence_beyond_truncation() {
    let batch = decode_batch(include_bytes!("../../../fixtures/events/batch.pb")).unwrap();
    let mut report = AuditReport::default();
    report.observe(&batch);
    assert_eq!(report.events as usize, batch.events.len());
    assert!(report.methods.contains_key("WebcastChatMessage"));
    let gift = decode_event_envelope(
        "WebcastGiftMessage",
        1,
        false,
        include_bytes!("../../../fixtures/events/deep-gift.pb"),
    );
    assert_eq!(gift.normalization_status, Status::Normalized);
    assert!(!gift.diagnostics().presence_complete);
    assert!(gift.diagnostics().absent_fields.is_empty());
}

#[test]
fn wire_emote_marker_is_visible_even_if_the_emote_has_no_usable_metadata() {
    let event = decode_event_envelope("WebcastEmoteChatMessage", 1, false, &[26, 0]);
    assert_eq!(event.wire_emote_entries(), Some((1, true)));
    let ttl_live_events::LiveEvent::EmoteChat(chat) = event.event else {
        panic!("expected emote chat");
    };
    assert!(chat.emotes.is_empty());
}
