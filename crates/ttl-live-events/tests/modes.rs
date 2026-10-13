use ttl_live_events::{decode_batch, decode_batch_with_mode, DecodeMode, EventError, LiveEvent};
use ttl_sign_core::proto::Writer;

const FIXTURE: &[u8] = include_bytes!("../../../fixtures/events/batch.pb");

#[test]
fn selective_modes_preserve_fixture_metadata_and_full_contract() {
    let legacy = decode_batch(FIXTURE).unwrap();
    for mode in [
        DecodeMode::Normalized,
        DecodeMode::Schema,
        DecodeMode::Full,
        DecodeMode::Raw,
    ] {
        let batch = decode_batch_with_mode(FIXTURE, mode).unwrap();
        assert_eq!(batch.cursor, legacy.cursor);
        assert_eq!(batch.internal_ext, legacy.internal_ext);
        assert_eq!(batch.need_ack, legacy.need_ack);
        assert_eq!(batch.heartbeat_duration, legacy.heartbeat_duration);
        assert_eq!(batch.push_server, legacy.push_server);
        assert_eq!(batch.events.len(), legacy.events.len());
        for (selected, original) in batch.events.iter().zip(&legacy.events) {
            assert_eq!(selected.method, original.raw.method);
            assert_eq!(selected.msg_id, original.raw.msg_id);
            assert_eq!(selected.is_history, original.raw.is_history);
            if matches!(mode, DecodeMode::Full | DecodeMode::Raw) {
                assert_eq!(selected.payload.as_ref().unwrap(), &original.raw.payload);
            } else {
                assert!(selected.payload.is_none());
            }
            if matches!(mode, DecodeMode::Full | DecodeMode::Normalized) {
                assert_eq!(selected.event.as_ref().unwrap(), &original.event);
            } else {
                assert!(selected.event.is_none());
            }
            if matches!(mode, DecodeMode::Full | DecodeMode::Schema) {
                assert_eq!(selected.schema.as_ref().unwrap(), &original.schema);
            } else {
                assert!(selected.schema.is_none());
            }
        }
    }
}

fn batch(method: &str, payload: &[u8], count: usize) -> Vec<u8> {
    let mut message = Writer::new();
    message
        .str_field(1, method)
        .bytes_field(2, payload)
        .u64_field(3, 42)
        .bool_field(8, true);
    let message = message.finish();
    let mut batch = Writer::new();
    for _ in 0..count {
        batch.bytes_field(1, &message);
    }
    batch.finish()
}

#[test]
fn unknown_and_malformed_normalized_events_preserve_bytes_once() {
    for method in ["WebcastFutureMessage", "WebcastChatMessage"] {
        let batch =
            decode_batch_with_mode(&batch(method, &[0xff], 1), DecodeMode::Normalized).unwrap();
        let event = &batch.events[0];
        assert!(event.payload.is_none());
        assert!(event.schema.is_none());
        assert_eq!(event.msg_id, 42);
        assert_eq!(
            event.event,
            Some(LiveEvent::Unknown {
                method: method.into(),
                payload: vec![0xff]
            })
        );
    }
}

#[test]
fn every_mode_enforces_batch_limits_and_rejects_corrupt_envelopes() {
    let many = batch("WebcastFutureMessage", &[8, 1], 8193);
    let large = vec![0; 16 * 1024 * 1024 + 1];
    for mode in [
        DecodeMode::Normalized,
        DecodeMode::Schema,
        DecodeMode::Full,
        DecodeMode::Raw,
    ] {
        assert!(matches!(
            decode_batch_with_mode(&many, mode),
            Err(EventError::TooLarge)
        ));
        assert!(matches!(
            decode_batch_with_mode(&large, mode),
            Err(EventError::TooLarge)
        ));
        assert!(decode_batch_with_mode(&[0xff], mode).is_err());
        assert_eq!(
            decode_batch_with_mode(&batch("Future", &[8, 1], 8192), mode)
                .unwrap()
                .events
                .len(),
            8192
        );
    }
}

#[test]
fn every_mode_bounds_oversized_events() {
    let encoded = batch("WebcastFutureMessage", &vec![0; 4 * 1024 * 1024 + 1], 1);
    for mode in [
        DecodeMode::Normalized,
        DecodeMode::Schema,
        DecodeMode::Full,
        DecodeMode::Raw,
    ] {
        let decoded = decode_batch_with_mode(&encoded, mode).unwrap();
        assert!(decoded.events[0].truncated);
        if let Some(payload) = &decoded.events[0].payload {
            assert_eq!(payload.len(), 4 * 1024 * 1024);
        }
    }
}

#[test]
fn selective_modes_keep_shared_field_budgets() {
    let payload = [8, 1].repeat(4097);
    for mode in [
        DecodeMode::Normalized,
        DecodeMode::Schema,
        DecodeMode::Full,
        DecodeMode::Raw,
    ] {
        let decoded = decode_batch_with_mode(&batch("Future", &payload, 1), mode).unwrap();
        assert!(decoded.events[0].truncated);
        let decoded = decode_batch_with_mode(&batch("Future", &payload[..8192], 17), mode).unwrap();
        assert!(decoded.events[16].truncated);
    }
}
