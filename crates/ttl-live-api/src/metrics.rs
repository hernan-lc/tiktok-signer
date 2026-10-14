//! Small dependency-free Prometheus exposition for the broker.

use std::fmt::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

const LATENCY_BUCKETS: &[f64] = &[
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0,
];

#[derive(Debug)]
pub struct Histogram {
    buckets: Vec<AtomicU64>,
    count: AtomicU64,
    sum_micros: AtomicU64,
}

impl Histogram {
    fn new() -> Self {
        Self {
            buckets: (0..LATENCY_BUCKETS.len())
                .map(|_| AtomicU64::new(0))
                .collect(),
            count: AtomicU64::new(0),
            sum_micros: AtomicU64::new(0),
        }
    }

    pub(crate) fn observe(&self, duration: Duration) {
        let seconds = duration.as_secs_f64();
        for (index, ceiling) in LATENCY_BUCKETS.iter().enumerate() {
            if seconds <= *ceiling {
                self.buckets[index].fetch_add(1, Ordering::Relaxed);
            }
        }
        self.count.fetch_add(1, Ordering::Relaxed);
        self.sum_micros.fetch_add(
            duration.as_micros().min(u64::MAX as u128) as u64,
            Ordering::Relaxed,
        );
    }

    fn render(&self, name: &str, output: &mut String) {
        let _ = writeln!(output, "# TYPE {name} histogram");
        for (index, ceiling) in LATENCY_BUCKETS.iter().enumerate() {
            let _ = writeln!(
                output,
                "{name}_bucket{{le=\"{ceiling}\"}} {}",
                self.buckets[index].load(Ordering::Relaxed)
            );
        }
        let _ = writeln!(
            output,
            "{name}_bucket{{le=\"+Inf\"}} {}",
            self.count.load(Ordering::Relaxed)
        );
        let _ = writeln!(
            output,
            "{name}_sum {}",
            self.sum_micros.load(Ordering::Relaxed) as f64 / 1_000_000.0
        );
        let _ = writeln!(
            output,
            "{name}_count {}",
            self.count.load(Ordering::Relaxed)
        );
    }
}

/// Metrics required to operate and cost-model the short-lived API path.
#[derive(Debug)]
pub struct Metrics {
    signer_observer: std::sync::Mutex<Option<ttl_sign_embedded::SignerObserver>>,
    pub active_live_connections: AtomicU64,
    pub live_connection_rejections_total: AtomicU64,
    pub connect_requests_total: AtomicU64,
    pub connect_success_total: AtomicU64,
    pub connect_offline_total: AtomicU64,
    pub connect_errors_total: AtomicU64,
    pub discovery_requests_total: AtomicU64,
    pub discovery_cache_hits_total: AtomicU64,
    pub discovery_cache_misses_total: AtomicU64,
    pub sign_requests_total: AtomicU64,
    pub sign_success_total: AtomicU64,
    pub sign_failure_total: AtomicU64,
    pub rate_limit_rejections_total: AtomicU64,
    pub tiktok_refusals_total: AtomicU64,
    pub guest_bootstrap_total: AtomicU64,
    pub guest_bootstrap_failures_total: AtomicU64,
    pub signer_in_flight: AtomicU64,
    pub signer_capacity: AtomicU64,
    pub connect_latency_seconds: Histogram,
    pub discovery_latency_seconds: Histogram,
    pub sign_latency_seconds: Histogram,
}

impl Default for Metrics {
    fn default() -> Self {
        Self {
            signer_observer: std::sync::Mutex::new(None),
            active_live_connections: AtomicU64::new(0),
            live_connection_rejections_total: AtomicU64::new(0),
            connect_requests_total: AtomicU64::new(0),
            connect_success_total: AtomicU64::new(0),
            connect_offline_total: AtomicU64::new(0),
            connect_errors_total: AtomicU64::new(0),
            discovery_requests_total: AtomicU64::new(0),
            discovery_cache_hits_total: AtomicU64::new(0),
            discovery_cache_misses_total: AtomicU64::new(0),
            sign_requests_total: AtomicU64::new(0),
            sign_success_total: AtomicU64::new(0),
            sign_failure_total: AtomicU64::new(0),
            rate_limit_rejections_total: AtomicU64::new(0),
            tiktok_refusals_total: AtomicU64::new(0),
            guest_bootstrap_total: AtomicU64::new(0),
            guest_bootstrap_failures_total: AtomicU64::new(0),
            signer_in_flight: AtomicU64::new(0),
            signer_capacity: AtomicU64::new(0),
            connect_latency_seconds: Histogram::new(),
            discovery_latency_seconds: Histogram::new(),
            sign_latency_seconds: Histogram::new(),
        }
    }
}

impl Metrics {
    pub fn observe_signer(&self, observer: ttl_sign_embedded::SignerObserver) {
        *self
            .signer_observer
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(observer);
    }
    pub fn render(&self) -> String {
        let mut output = String::new();
        for (name, value) in [
            ("active_live_connections", &self.active_live_connections),
            (
                "live_connection_rejections_total",
                &self.live_connection_rejections_total,
            ),
            ("connect_requests_total", &self.connect_requests_total),
            ("connect_success_total", &self.connect_success_total),
            ("connect_offline_total", &self.connect_offline_total),
            ("connect_errors_total", &self.connect_errors_total),
            ("discovery_requests_total", &self.discovery_requests_total),
            (
                "discovery_cache_hits_total",
                &self.discovery_cache_hits_total,
            ),
            (
                "discovery_cache_misses_total",
                &self.discovery_cache_misses_total,
            ),
            ("sign_requests_total", &self.sign_requests_total),
            ("sign_success_total", &self.sign_success_total),
            ("sign_failure_total", &self.sign_failure_total),
            (
                "rate_limit_rejections_total",
                &self.rate_limit_rejections_total,
            ),
            ("tiktok_refusals_total", &self.tiktok_refusals_total),
            ("guest_bootstrap_total", &self.guest_bootstrap_total),
            (
                "guest_bootstrap_failures_total",
                &self.guest_bootstrap_failures_total,
            ),
            ("signer_in_flight", &self.signer_in_flight),
            ("signer_capacity", &self.signer_capacity),
        ] {
            let metric_type = if name.ends_with("_total") {
                "counter"
            } else {
                "gauge"
            };
            let _ = writeln!(output, "# TYPE {name} {metric_type}");
            let _ = writeln!(output, "{name} {}", value.load(Ordering::Relaxed));
        }
        if let Some(observer) = self
            .signer_observer
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
        {
            let snapshot = observer.metrics();
            for (name, value, kind) in [
                ("signer_queue_depth", snapshot.queue_depth as u64, "gauge"),
                (
                    "signer_queue_rejections_total",
                    snapshot.queue_rejections,
                    "counter",
                ),
                (
                    "signer_worker_failures_total",
                    snapshot.worker_failures,
                    "counter",
                ),
            ] {
                let _ = writeln!(output, "# TYPE {name} {kind}\n{name} {value}");
            }
        }
        self.connect_latency_seconds
            .render("connect_latency_seconds", &mut output);
        self.discovery_latency_seconds
            .render("discovery_latency_seconds", &mut output);
        self.sign_latency_seconds
            .render("sign_latency_seconds", &mut output);
        output
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ttl_sign_embedded::{EmbeddedError, Engine, Profile, Signer};
    struct FakeEngine;
    impl Engine for FakeEngine {
        const NAME: &'static str = "metrics-test";
        fn start(_: &str, _: &str) -> Result<Self, EmbeddedError> {
            Ok(Self)
        }
        fn sign(&mut self, url: &str, _: &str) -> Result<String, String> {
            Ok(url.into())
        }
    }
    #[test]
    fn signer_metrics_are_live_and_do_not_own_the_worker() {
        let signer = Signer::<FakeEngine>::new("", Profile::default()).unwrap();
        let metrics = Metrics::default();
        metrics.observe_signer(signer.observer());
        let rendered = metrics.render();
        assert!(rendered.contains("# TYPE signer_queue_depth gauge\nsigner_queue_depth 0"));
        assert!(rendered.contains("signer_queue_rejections_total 0"));
        assert!(rendered.contains("signer_worker_failures_total 0"));
        drop(signer);
        assert!(metrics.render().contains("signer_queue_depth 0"));
    }
}
