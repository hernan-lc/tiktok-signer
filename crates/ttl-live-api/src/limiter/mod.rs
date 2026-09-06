//! API-key authentication and local request quotas.

mod limits;

pub use limits::{ApiKeyStore, RateDecision, RateLimiter};
