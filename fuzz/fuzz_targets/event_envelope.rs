#![no_main]
use libfuzzer_sys::fuzz_target;
use ttl_live_events::{decode_event_envelope,JsonMode};
fuzz_target!(|data: &[u8]| {
    if data.len() > 16 * 1024 * 1024 { return; }
    let split = data.first().copied().unwrap_or(0) as usize;
    let split = split.min(data.len());
    let method = String::from_utf8_lossy(&data[..split]);
    for method in [method.as_ref(),"WebcastChatMessage","WebcastGiftMessage","WebcastRoomUserSeqMessage"] {
        let e = decode_event_envelope(method,u64::MAX,false,&data[split..]);
        let _ = e.to_json(JsonMode::Full);
    }
    let _ = ttl_live_events::decode_batch(data);
    for mode in [ttl_live_events::DecodeMode::Raw, ttl_live_events::DecodeMode::Normalized,
        ttl_live_events::DecodeMode::Schema, ttl_live_events::DecodeMode::Full] {
        let _ = ttl_live_events::decode_batch_with_mode(data, mode);
    }
});
