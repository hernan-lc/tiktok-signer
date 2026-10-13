//! Offline benchmark: cargo run --release -p ttl-live-events --example decode_bench.
use std::hint::black_box;
use std::time::Instant;

fn main() {
    let payload = include_bytes!("../../../fixtures/events/batch.pb");
    let mode = match std::env::args().nth(1).as_deref() {
        Some("normalized") => ttl_live_events::DecodeMode::Normalized,
        Some("schema") => ttl_live_events::DecodeMode::Schema,
        Some("raw") => ttl_live_events::DecodeMode::Raw,
        _ => ttl_live_events::DecodeMode::Full,
    };
    let iterations = 10_000;
    let events = ttl_live_events::decode_batch(payload).unwrap().events.len();
    for _ in 0..100 {
        black_box(ttl_live_events::decode_batch_with_mode(black_box(payload), mode).unwrap());
    }
    let mut samples = Vec::with_capacity(iterations);
    let start = Instant::now();
    for _ in 0..iterations {
        let before = Instant::now();
        black_box(ttl_live_events::decode_batch_with_mode(black_box(payload), mode).unwrap());
        samples.push(before.elapsed().as_nanos());
    }
    let elapsed = start.elapsed();
    samples.sort_unstable();
    println!("iterations={iterations} events_per_batch={events} events_per_second={:.0} batch_ns_p50={} batch_ns_p95={} batch_ns_p99={}",
        (iterations * events) as f64 / elapsed.as_secs_f64(),
        samples[iterations / 2], samples[iterations * 95 / 100], samples[iterations * 99 / 100]);
}
