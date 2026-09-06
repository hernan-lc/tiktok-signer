//! Runtime configuration for the connection broker.

use std::collections::HashMap;
use std::time::Duration;

/// Configuration is intentionally made from environment variables at the binary boundary. The
/// service and its tests receive a value object, which keeps infrastructure tuning out of the
/// request path and makes the defaults easy to exercise without a network.
#[derive(Debug, Clone)]
pub struct AppConfig {
    pub bind: String,
    pub max_concurrent_signs: usize,
    pub max_concurrent_discovery: usize,
    pub max_concurrent_requests: usize,
    pub room_cache_ttl: Duration,
    pub offline_cache_ttl: Duration,
    pub not_found_cache_ttl: Duration,
    pub request_timeout: Duration,
    pub connect_requests_per_minute: u32,
    pub unique_creators_per_minute: u32,
    pub room_cache_capacity: usize,
    /// Maps an API key to a non-secret customer/account label.
    pub api_keys: HashMap<String, String>,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            // Loopback by default: with empty `API_KEYS` anyone who can reach the port can
            // mint tickets, so listening on all interfaces must stay an explicit choice
            // (`--bind`, `HTTP_BIND`/`TTL_BIND`), which prints a startup warning.
            bind: "127.0.0.1:8080".into(),
            max_concurrent_signs: 32,
            max_concurrent_discovery: 64,
            max_concurrent_requests: 256,
            room_cache_ttl: Duration::from_secs(30),
            offline_cache_ttl: Duration::from_secs(15),
            not_found_cache_ttl: Duration::from_secs(45),
            request_timeout: Duration::from_secs(10),
            connect_requests_per_minute: 120,
            unique_creators_per_minute: 60,
            room_cache_capacity: 10_000,
            api_keys: HashMap::new(),
        }
    }
}

impl AppConfig {
    /// Read service settings from the environment.
    ///
    /// `API_KEYS` is a comma-separated list of `secret=customer_id` pairs. A key without `=` is
    /// accepted as a development convenience and receives the redacted label `api-key` internally.
    pub fn from_env() -> Self {
        let defaults = Self::default();
        let mut config = Self {
            bind: env_string("HTTP_BIND", env_string("TTL_BIND", defaults.bind)),
            max_concurrent_signs: env_usize("MAX_CONCURRENT_SIGNS", defaults.max_concurrent_signs),
            max_concurrent_discovery: env_usize(
                "MAX_CONCURRENT_DISCOVERY",
                defaults.max_concurrent_discovery,
            ),
            max_concurrent_requests: env_usize(
                "MAX_CONCURRENT_REQUESTS",
                defaults.max_concurrent_requests,
            ),
            room_cache_ttl: Duration::from_secs(env_u64(
                "ROOM_CACHE_TTL_SECONDS",
                defaults.room_cache_ttl.as_secs(),
            )),
            offline_cache_ttl: Duration::from_secs(env_u64(
                "OFFLINE_CACHE_TTL_SECONDS",
                defaults.offline_cache_ttl.as_secs(),
            )),
            not_found_cache_ttl: Duration::from_secs(env_u64(
                "NOT_FOUND_CACHE_TTL_SECONDS",
                defaults.not_found_cache_ttl.as_secs(),
            )),
            request_timeout: Duration::from_millis(env_u64(
                "REQUEST_TIMEOUT_MS",
                defaults.request_timeout.as_millis() as u64,
            )),
            connect_requests_per_minute: env_u32(
                "CONNECT_REQUESTS_PER_MINUTE",
                defaults.connect_requests_per_minute,
            ),
            unique_creators_per_minute: env_u32(
                "UNIQUE_CREATORS_PER_MINUTE",
                defaults.unique_creators_per_minute,
            ),
            room_cache_capacity: env_usize("ROOM_CACHE_CAPACITY", defaults.room_cache_capacity),
            api_keys: parse_api_keys(std::env::var("API_KEYS").ok().as_deref()),
        };

        // Zero-sized semaphores would turn every request into a permanent capacity error. Keep
        // configuration forgiving at startup while still allowing zero request quotas to be used
        // intentionally for a disabled tenant through the rate limiter.
        config.max_concurrent_signs = config.max_concurrent_signs.max(1);
        config.max_concurrent_discovery = config.max_concurrent_discovery.max(1);
        config.max_concurrent_requests = config.max_concurrent_requests.max(1);
        config.room_cache_capacity = config.room_cache_capacity.max(1);
        config
    }
}

/// Whether a `host:port` bind address stays on this machine. Used for the startup
/// warning when the broker would otherwise mint tickets for anyone on the network.
pub fn binds_loopback(bind: &str) -> bool {
    let host = bind
        .rsplit_once(':')
        .map(|(host, _)| host)
        .unwrap_or(bind);
    let host = host.trim().trim_matches(|c| c == '[' || c == ']');
    host.eq_ignore_ascii_case("localhost")
        || host == "::1"
        || host == "127.0.0.1"
        || host.starts_with("127.")
}

fn env_string(name: &str, default: String) -> String {    std::env::var(name)
        .ok()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or(default)
}

fn env_u64(name: &str, default: u64) -> u64 {
    std::env::var(name)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn env_u32(name: &str, default: u32) -> u32 {
    std::env::var(name)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

fn env_usize(name: &str, default: usize) -> usize {
    std::env::var(name)
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(default)
}

pub(crate) fn parse_api_keys(raw: Option<&str>) -> HashMap<String, String> {
    let mut keys = HashMap::new();
    for item in raw.unwrap_or_default().split(',') {
        let item = item.trim();
        if item.is_empty() {
            continue;
        }
        let (key, customer) = item.split_once('=').unwrap_or((item, "api-key"));
        if !key.is_empty() {
            keys.insert(
                key.to_owned(),
                if customer.trim().is_empty() {
                    "api-key".into()
                } else {
                    customer.trim().to_owned()
                },
            );
        }
    }
    keys
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_the_initial_operating_profile() {
        let config = AppConfig::default();
        assert_eq!(config.room_cache_ttl, Duration::from_secs(30));
        assert_eq!(config.offline_cache_ttl, Duration::from_secs(15));
        assert_eq!(config.max_concurrent_signs, 32);
    }

    #[test]
    fn default_bind_stays_on_loopback() {
        assert_eq!(AppConfig::default().bind, "127.0.0.1:8080");
        assert!(binds_loopback("127.0.0.1:8080"));
        assert!(binds_loopback("localhost:8080"));
        assert!(binds_loopback("[::1]:8080"));
        assert!(!binds_loopback("0.0.0.0:8080"));
        assert!(!binds_loopback("[::]:8080"));
        assert!(!binds_loopback("192.168.1.10:8080"));
    }

    #[test]
    fn api_keys_are_parsed_without_exposing_values_in_labels() {        let keys = parse_api_keys(Some("secret-a=acme,secret-b, ,secret-c= team "));
        assert_eq!(keys.get("secret-a"), Some(&"acme".to_string()));
        assert_eq!(keys.get("secret-b"), Some(&"api-key".to_string()));
        assert_eq!(keys.get("secret-c"), Some(&"team".to_string()));
    }
}
