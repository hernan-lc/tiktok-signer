#![no_main]
use libfuzzer_sys::fuzz_target;
use ttl_live_events::{decode_event_envelope, JsonMode};
fuzz_target!(|data: &[u8]| {
    if data.len() > 16 * 1024 * 1024 {
        return;
    }
    let split = data.first().copied().unwrap_or(0) as usize;
    let split = split.min(data.len());
    let method = String::from_utf8_lossy(&data[..split]);
    for method in [
        method.as_ref(),
        "WebcastChatMessage",
        "WebcastGiftMessage",
        "WebcastRoomUserSeqMessage",
    ] {
        let e = decode_event_envelope(method, u64::MAX, false, &data[split..]);
        let _ = e.to_json(JsonMode::Full);
    }
    let legacy = ttl_live_events::decode_batch(data);
    if let Ok(batch) = &legacy {
        let mut audit = ttl_live_events::AuditReport::default();
        audit.observe(batch);
        assert_eq!(audit.events as usize, batch.events.len());
    }
    for mode in [
        ttl_live_events::DecodeMode::Raw,
        ttl_live_events::DecodeMode::Normalized,
        ttl_live_events::DecodeMode::Schema,
        ttl_live_events::DecodeMode::Full,
    ] {
        let selected = ttl_live_events::decode_batch_with_mode(data, mode);
        assert_eq!(legacy.is_ok(), selected.is_ok());
        if let (Ok(legacy), Ok(selected)) = (&legacy, selected) {
            assert_eq!(legacy.events.len(), selected.events.len());
            for (original, event) in legacy.events.iter().zip(selected.events) {
                assert_eq!(original.raw.method, event.method);
                assert_eq!(original.raw.msg_id, event.msg_id);
                assert_eq!(original.timestamp(), event.timestamp);
                if matches!(
                    mode,
                    ttl_live_events::DecodeMode::Full | ttl_live_events::DecodeMode::Normalized
                ) {
                    assert_eq!(Some(&original.event), event.event.as_ref());
                    assert_eq!(original.normalization_status, event.normalization_status);
                }
            }
        }
    }
});
