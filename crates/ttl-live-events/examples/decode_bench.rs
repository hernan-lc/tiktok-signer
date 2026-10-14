//! Offline benchmark: cargo run --release -p ttl-live-events --example decode_bench.
#[path = "../../../scripts/bench/counting_allocator.rs"]
mod counting_allocator;
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
    let legacy = std::env::args().nth(1).as_deref() == Some("legacy");
    let decode = || {
        if legacy {
            black_box(ttl_live_events::decode_batch(black_box(payload)).unwrap());
        } else {
            black_box(ttl_live_events::decode_batch_with_mode(black_box(payload), mode).unwrap());
        }
    };
    let iterations: usize = std::env::args()
        .nth(2)
        .map(|value| value.parse().expect("iteration count"))
        .unwrap_or(10_000);
    assert!(
        (100..=1_000_000).contains(&iterations),
        "iterations must be 100..=1000000"
    );
    let events = ttl_live_events::decode_batch(payload).unwrap().events.len();
    for _ in 0..100 {
        decode();
    }
    let mut samples = Vec::with_capacity(iterations);
    counting_allocator::reset();
    let start = Instant::now();
    for _ in 0..iterations {
        let before = Instant::now();
        decode();
        samples.push(before.elapsed().as_nanos());
    }
    let elapsed = start.elapsed();
    counting_allocator::report(iterations * events);
    samples.sort_unstable();
    println!("iterations={iterations} events_per_batch={events} events_per_second={:.0} batch_ns_p50={} batch_ns_p95={} batch_ns_p99={}",
        (iterations * events) as f64 / elapsed.as_secs_f64(),
        samples[iterations / 2], samples[iterations * 95 / 100], samples[iterations * 99 / 100]);
}
