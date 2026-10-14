//! Offline signing benchmarks. TTL_BUNDLE selects a local verified bundle; no HTTP.
use std::sync::Arc;
use std::time::{Duration, Instant};
use ttl_live_discovery::SigningProduct;
use ttl_sign_embedded::{EmbeddedSigner, Profile, SignerConfig};
const URL: &str = "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300000000000000001&aid=1988&version_code=180800";
fn memory(label: &str) {
    if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
        let fields: Vec<_> = status
            .lines()
            .filter(|line| {
                line.starts_with("VmRSS:")
                    || line.starts_with("VmHWM:")
                    || line.starts_with("Threads:")
            })
            .collect();
        println!("{label}: {}", fields.join(" "));
    }
}
#[tokio::main(flavor = "current_thread")]
async fn main() {
    let args: Vec<_> = std::env::args().collect();
    let bundle = std::fs::read_to_string(
        std::env::var("TTL_BUNDLE").expect("set TTL_BUNDLE to a local bundle"),
    )
    .unwrap();
    memory("baseline");
    if args.get(1).is_some_and(|s| s == "soak") {
        let seconds: u64 = args.get(2).map_or(1800, |v| v.parse().unwrap());
        let signer = EmbeddedSigner::new(bundle, Profile::default()).unwrap();
        let start = Instant::now();
        let mut interval = tokio::time::interval(Duration::from_millis(100));
        interval.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
        let mut count = 0u64;
        while start.elapsed().as_secs() < seconds {
            interval.tick().await;
            assert!(signer
                .sign_with(URL, SigningProduct::WsDirect)
                .await
                .unwrap()
                .contains("X-Gnarly="));
            count += 1;
            if count.is_multiple_of(100) {
                memory(&format!(
                    "elapsed={} requests={count} queue={}",
                    start.elapsed().as_secs(),
                    signer.metrics().queue_depth
                ));
            }
        }
        signer.shutdown().await;
        memory("after_shutdown");
        println!("soak_seconds={seconds} completed={count}");
        return;
    }
    let workers: usize = args.get(1).map_or(1, |v| v.parse().unwrap());
    let concurrency: usize = args.get(2).map_or(10, |v| v.parse().unwrap());
    let iterations: usize = args.get(3).map_or(1000, |v| v.parse().unwrap());
    assert!(
        (1..=16).contains(&workers)
            && (1..=128).contains(&concurrency)
            && (1..=1_000_000).contains(&iterations)
    );
    let signers: Vec<_> = (0..workers)
        .map(|_| {
            Arc::new(
                EmbeddedSigner::with_config(
                    bundle.clone(),
                    Profile::default(),
                    SigningProduct::WsDirect,
                    SignerConfig {
                        queue_capacity: 128,
                        ..SignerConfig::default()
                    },
                )
                .unwrap(),
            )
        })
        .collect();
    for signer in &signers {
        signer
            .sign_with(URL, SigningProduct::WsDirect)
            .await
            .unwrap();
    }
    memory("warm");
    let mut timings = Vec::with_capacity(iterations);
    let start = Instant::now();
    for offset in (0..iterations).step_by(concurrency) {
        let mut tasks = tokio::task::JoinSet::new();
        for index in offset..(offset + concurrency).min(iterations) {
            let signer = signers[index % workers].clone();
            tasks.spawn(async move {
                let started = Instant::now();
                signer
                    .sign_with(URL, SigningProduct::WsDirect)
                    .await
                    .unwrap();
                started.elapsed().as_nanos()
            });
        }
        while let Some(result) = tasks.join_next().await {
            timings.push(result.unwrap());
        }
    }
    let elapsed = start.elapsed();
    timings.sort_unstable();
    println!("engine={} workers={workers} concurrency={concurrency} requests={iterations} requests_per_second={:.2} request_ns_p50={} request_ns_p95={} request_ns_p99={}",
        ttl_sign_embedded::ENGINE, iterations as f64 / elapsed.as_secs_f64(), timings[iterations/2], timings[iterations*95/100], timings[iterations*99/100]);
    for signer in signers {
        Arc::try_unwrap(signer).ok().unwrap().shutdown().await;
    }
    memory("after_shutdown");
}
