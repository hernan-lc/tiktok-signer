//! The `action` -> kind table is the contract every language binding must
//! agree on. It lives in one JSON fixture that the Rust, TypeScript and Java
//! tests all read, so a new rule added to one implementation without the others
//! fails here instead of shipping.
//!
//! The fixture deliberately contains only values with a *confirmed* meaning
//! (`1`, `3`) plus values that must stay unclassified (`0` and the unobserved
//! codes). Nothing in the table is inferred from counters.

use std::fs;
use std::path::{Path, PathBuf};

use serde_json::Value;
use ttl_live_events::social::classify_action;
use ttl_live_events::{LiveEvent, SocialKind};

fn fixture_path() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/events/social-classification.json")
}

fn kind_name(kind: SocialKind) -> &'static str {
    match kind {
        SocialKind::Follow => "follow",
        SocialKind::Share => "share",
        SocialKind::Other => "other",
        SocialKind::Unknown => "unknown",
    }
}

fn confidence_name(confidence: ttl_live_events::SocialConfidence) -> &'static str {
    match confidence {
        ttl_live_events::SocialConfidence::Confirmed => "confirmed",
        ttl_live_events::SocialConfidence::Inferred => "inferred",
        ttl_live_events::SocialConfidence::Unknown => "unknown",
    }
}

#[test]
fn classification_table_matches_the_shared_golden() {
    let raw = fs::read_to_string(fixture_path()).expect("classification fixture is readable");
    let fixture: Value = serde_json::from_str(&raw).expect("classification fixture is valid JSON");
    let cases = fixture["cases"]
        .as_array()
        .expect("fixture has a cases array");
    assert!(!cases.is_empty(), "fixture must contain at least one case");

    for case in cases {
        let action = case["action"].as_i64().expect("case has an integer action");
        let expected_kind = case["kind"].as_str().expect("case has a kind");
        let expected_confidence = case["confidence"].as_str().expect("case has a confidence");

        let classification = classify_action(action);
        assert_eq!(
            kind_name(classification.kind),
            expected_kind,
            "kind mismatch for action {action}"
        );
        assert_eq!(
            confidence_name(classification.confidence),
            expected_confidence,
            "confidence mismatch for action {action}"
        );
        assert_eq!(
            classification.raw_action, action,
            "raw action must survive classification"
        );
    }
}

/// The confirmed values are the only ones that may ever be treated as a
/// follow or a share. This is the guard against a future "just fold unknown
/// into share" change.
#[test]
fn only_confirmed_actions_are_classified() {
    for action in [-1i64, 0, 2, 4, 5, 8, 97, 1000, i64::MAX] {
        let classification = classify_action(action);
        assert!(
            matches!(classification.kind, SocialKind::Other | SocialKind::Unknown),
            "action {action} must not be classified as {:?}",
            classification.kind
        );
    }
}

/// A share fixture carrying a cumulative `follow_count` must normalise as a
/// share. This is the end-to-end form of the Android regression.
#[test]
fn share_fixture_with_cumulative_follow_count_is_still_a_share() {
    let payload =
        fs::read(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/events/share.pb"))
            .expect("share.pb exists; run the make-fixtures example");
    match ttl_live_events::decode_event(ttl_live_events::method::SOCIAL, &payload) {
        LiveEvent::Social(social) => {
            assert!(
                social.follow_count > 0,
                "fixture keeps a cumulative follow count"
            );
            assert_eq!(social.kind(), SocialKind::Share);
        }
        other => panic!("expected a social event, got {other:?}"),
    }
}
