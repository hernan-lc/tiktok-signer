use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use axum::http::HeaderValue;
use tokio::sync::Mutex;

use crate::error::ApiError;

/// A non-secret API-key lookup table. The raw key is only compared in memory and is never used in
/// tracing fields or an error message.
#[derive(Debug, Clone)]
pub struct ApiKeyStore {
    keys: HashMap<String, String>,
}

impl ApiKeyStore {
    pub fn new(keys: HashMap<String, String>) -> Self {
        Self { keys }
    }

    pub fn authenticate(&self, authorization: Option<&HeaderValue>) -> Result<String, ApiError> {
        if self.keys.is_empty() {
            // Local deployments may intentionally run without authentication. If a bearer is
            // present, still place it in the authenticated quota bucket without echoing it.
            return Ok("anonymous".into());
        }

        let Some(header) = authorization else {
            return Err(ApiError::AuthenticationRequired);
        };
        let value = header.to_str().map_err(|_| ApiError::InvalidApiKey)?;
        let Some(key) = value.strip_prefix("Bearer ") else {
            return Err(ApiError::InvalidApiKey);
        };
        self.keys.get(key).cloned().ok_or(ApiError::InvalidApiKey)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RateDecision {
    pub retry_after_ms: u64,
}

#[derive(Debug)]
struct Bucket {
    started_at: Instant,
    requests: u32,
    creators: HashSet<String>,
}

/// A fixed one-minute local quota. It protects each instance; horizontal deployments can add a
/// distributed counter later without changing the public endpoint contract.
#[derive(Debug)]
pub struct RateLimiter {
    buckets: Mutex<HashMap<String, Bucket>>,
    request_limit: u32,
    creator_limit: u32,
    creator_tracking_capacity: usize,
    window: Duration,
    capacity: usize,
}

impl RateLimiter {
    pub fn new(request_limit: u32, creator_limit: u32, capacity: usize) -> Self {
        Self {
            buckets: Mutex::new(HashMap::new()),
            request_limit,
            creator_limit,
            // Even when a deployment disables the logical quota with zero, keep the set used for
            // diagnostics bounded. A later distributed limiter can replace this implementation
            // without changing the API contract.
            creator_tracking_capacity: creator_limit.max(request_limit).max(10_000) as usize,
            window: Duration::from_secs(60),
            capacity: capacity.max(1),
        }
    }

    pub async fn check(&self, customer_id: &str, unique_id: &str) -> Result<(), RateDecision> {
        let now = Instant::now();
        let mut buckets = self.buckets.lock().await;
        if buckets.len() >= self.capacity && !buckets.contains_key(customer_id) {
            prune(&mut buckets, now, self.window);
        }
        if buckets.len() >= self.capacity && !buckets.contains_key(customer_id) {
            return Err(RateDecision {
                retry_after_ms: 1_000,
            });
        }

        let bucket = buckets
            .entry(customer_id.to_owned())
            .or_insert_with(|| Bucket {
                started_at: now,
                requests: 0,
                creators: HashSet::new(),
            });
        if now.duration_since(bucket.started_at) >= self.window {
            bucket.started_at = now;
            bucket.requests = 0;
            bucket.creators.clear();
        }

        let new_creator = !bucket.creators.contains(unique_id);
        if (self.request_limit > 0 && bucket.requests >= self.request_limit)
            || (self.creator_limit > 0
                && new_creator
                && bucket.creators.len() as u32 >= self.creator_limit)
        {
            let retry_after_ms = self
                .window
                .saturating_sub(now.duration_since(bucket.started_at))
                .as_millis()
                .max(1) as u64;
            return Err(RateDecision { retry_after_ms });
        }

        bucket.requests = bucket.requests.saturating_add(1);
        if bucket.creators.len() < self.creator_tracking_capacity {
            bucket.creators.insert(unique_id.to_owned());
        }
        Ok(())
    }
}

fn prune(buckets: &mut HashMap<String, Bucket>, now: Instant, window: Duration) {
    buckets.retain(|_, bucket| now.duration_since(bucket.started_at) < window);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn request_and_creator_limits_share_one_decision() {
        let limiter = RateLimiter::new(1, 10, 10);
        assert!(limiter.check("customer", "one").await.is_ok());
        let second = limiter.check("customer", "two").await.unwrap_err();
        assert!(second.retry_after_ms > 0);
    }

    #[test]
    fn configured_keys_require_a_bearer_header() {
        let store = ApiKeyStore::new(HashMap::from([(
            String::from("secret"),
            String::from("acme"),
        )]));
        assert!(matches!(
            store.authenticate(None),
            Err(ApiError::AuthenticationRequired)
        ));
        assert_eq!(
            store
                .authenticate(Some(&HeaderValue::from_static("Bearer secret")))
                .unwrap(),
            "acme"
        );
        assert!(matches!(
            store.authenticate(Some(&HeaderValue::from_static("Bearer wrong"))),
            Err(ApiError::InvalidApiKey)
        ));
    }
}
