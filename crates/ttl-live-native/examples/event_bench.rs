//! Offline native event construction/drop benchmark, with optional schema traversal.
#[path = "../../../scripts/bench/counting_allocator.rs"]
mod counting_allocator;
use std::hint::black_box;
use std::time::Instant;
use ttl_live::*;
fn main() {
    let payload = include_bytes!("../../../fixtures/events/chat.pb");
    let schema = std::env::args().nth(1).as_deref() == Some("schema");
    let iterations = 10_000;
    let mut samples = Vec::with_capacity(iterations);
    for i in 0..iterations + 100 {
        if i == 100 {
            counting_allocator::reset();
        }
        let start = Instant::now();
        // Inputs are fixed valid bytes. All returned pointers stay within event lifetime.
        unsafe {
            let event = ttl_event_decode(
                c"WebcastChatMessage".as_ptr(),
                18,
                i as u64,
                false,
                payload.as_ptr(),
                payload.len(),
            );
            assert!(!event.is_null());
            if schema {
                black_box(ttl_object_field_count(ttl_event_root(event)));
            } else {
                black_box(ttl_event_raw_size(event));
            }
            ttl_event_free(event);
        }
        if i >= 100 {
            samples.push(start.elapsed().as_nanos());
        }
    }
    counting_allocator::report(iterations);
    samples.sort_unstable();
    let seconds = samples.iter().sum::<u128>() as f64 / 1e9;
    println!(
        "schema={schema} events_per_second={:.0} event_ns_p50={} event_ns_p95={} event_ns_p99={}",
        iterations as f64 / seconds,
        samples[5000],
        samples[9500],
        samples[9900]
    );
}
