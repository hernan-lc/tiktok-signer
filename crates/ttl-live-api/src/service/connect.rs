use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Instant;

use serde::Serialize;
use tokio::sync::{Mutex, OnceCell, OwnedSemaphorePermit, Semaphore};
use tracing::{debug, info, warn};

use crate::cache::RoomCache;
use crate::config::AppConfig;
use crate::error::ApiError;
use crate::limiter::{ApiKeyStore, RateDecision, RateLimiter};
use crate::metrics::Metrics;

use super::resolver::{ResolveFailure, RoomResolver};
use super::signer::ConnectionSigner;

const MAX_UNIQUE_ID_LENGTH: usize = 24;

type ResolutionFlight = Arc<OnceCell<Result<RoomResolution, ResolveFailure>>>;

/// The only public identifier accepted by the broker. The canonical form is lower-level TikTok's
/// username without a leading `@`; room ids never enter this request type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RoomResolution {
    Live {
        room_id: String,
        status: i64,
        nickname: String,
    },
    Offline {
        room_id: Option<String>,
        status: i64,
        nickname: String,
    },
    NotFound,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectResponse {
    pub version: u8,
    pub unique_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub room_id: Option<String>,
    pub status: ConnectStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub connection: Option<ConnectionDescriptor>,
}

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ConnectStatus {
    Live,
    Offline,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ConnectionDescriptor {
    pub url: String,
    pub cookies: String,
    pub user_agent: String,
}

impl ConnectResponse {
    fn live(
        unique_id: String,
        room_id: String,
        url: String,
        cookies: String,
        user_agent: String,
    ) -> Self {
        Self {
            version: 1,
            unique_id,
            room_id: Some(room_id),
            status: ConnectStatus::Live,
            connection: Some(ConnectionDescriptor {
                url,
                cookies,
                user_agent,
            }),
        }
    }

    fn offline(unique_id: String) -> Self {
        Self {
            version: 1,
            unique_id,
            room_id: None,
            status: ConnectStatus::Offline,
            connection: None,
        }
    }
}

/// Normalize and validate the only public input.
pub fn normalize_unique_id(input: &str) -> Result<String, ApiError> {
    let input = input.trim();
    if input.starts_with("@@") {
        return Err(ApiError::InvalidUniqueId);
    }
    let normalized = input.strip_prefix('@').unwrap_or(input);
    if normalized.is_empty()
        || normalized.len() > MAX_UNIQUE_ID_LENGTH
        || !normalized
            .chars()
            .all(|character| character.is_ascii_alphanumeric() || matches!(character, '.' | '_'))
    {
        return Err(ApiError::InvalidUniqueId);
    }
    Ok(normalized.to_owned())
}

/// The broker's state. Every operation that can touch TikTok is behind a bounded semaphore, and
/// every room resolution is shared per canonical unique id through `OnceCell` single-flight.
pub struct ConnectService {
    pub(crate) config: AppConfig,
    pub(crate) resolver: Arc<dyn RoomResolver>,
    pub(crate) signer: Arc<dyn ConnectionSigner>,
    pub(crate) cache: RoomCache,
    pub(crate) flights: Mutex<HashMap<String, ResolutionFlight>>,
    pub(crate) discovery_slots: Arc<Semaphore>,
    pub(crate) signing_slots: Arc<Semaphore>,
    pub(crate) request_slots: Arc<Semaphore>,
    pub(crate) api_keys: ApiKeyStore,
    pub(crate) rate_limiter: RateLimiter,
    pub(crate) metrics: Arc<Metrics>,
    request_ids: AtomicU64,
}

impl ConnectService {
    pub fn new(
        resolver: Arc<dyn RoomResolver>,
        signer: Arc<dyn ConnectionSigner>,
        config: AppConfig,
    ) -> Arc<Self> {
        Self::with_metrics(resolver, signer, config, Arc::new(Metrics::default()))
    }

    pub fn with_metrics(
        resolver: Arc<dyn RoomResolver>,
        signer: Arc<dyn ConnectionSigner>,
        config: AppConfig,
        metrics: Arc<Metrics>,
    ) -> Arc<Self> {
        metrics
            .signer_capacity
            .store(config.max_concurrent_signs as u64, Ordering::Relaxed);
        Arc::new(Self {
            cache: RoomCache::new(
                config.room_cache_ttl,
                config.offline_cache_ttl,
                config.not_found_cache_ttl,
                config.room_cache_capacity,
            ),
            flights: Mutex::new(HashMap::new()),
            discovery_slots: Arc::new(Semaphore::new(config.max_concurrent_discovery)),
            signing_slots: Arc::new(Semaphore::new(config.max_concurrent_signs)),
            request_slots: Arc::new(Semaphore::new(config.max_concurrent_requests)),
            api_keys: ApiKeyStore::new(config.api_keys.clone()),
            rate_limiter: RateLimiter::new(
                config.connect_requests_per_minute,
                config.unique_creators_per_minute,
                config.room_cache_capacity,
            ),
            config,
            resolver,
            signer,
            metrics,
            request_ids: AtomicU64::new(0),
        })
    }

    pub fn metrics(&self) -> &Arc<Metrics> {
        &self.metrics
    }

    pub fn next_request_id(&self) -> u64 {
        self.request_ids.fetch_add(1, Ordering::Relaxed) + 1
    }

    pub(crate) fn signer_ready(&self) -> bool {
        self.signer.ready()
    }

    pub fn authenticate(
        &self,
        authorization: Option<&axum::http::HeaderValue>,
    ) -> Result<String, ApiError> {
        self.api_keys.authenticate(authorization)
    }

    pub async fn check_rate_limit(
        &self,
        customer_id: &str,
        unique_id: &str,
    ) -> Result<(), ApiError> {
        match self.rate_limiter.check(customer_id, unique_id).await {
            Ok(()) => Ok(()),
            Err(RateDecision { retry_after_ms }) => {
                self.metrics
                    .rate_limit_rejections_total
                    .fetch_add(1, Ordering::Relaxed);
                Err(ApiError::RateLimited {
                    message: "Too many connection requests".into(),
                    retry_after_ms,
                })
            }
        }
    }

    pub fn try_request_slot(&self) -> Result<OwnedSemaphorePermit, ApiError> {
        self.request_slots.clone().try_acquire_owned().map_err(|_| {
            self.metrics
                .rate_limit_rejections_total
                .fetch_add(1, Ordering::Relaxed);
            ApiError::RateLimited {
                message: "Connection request capacity is currently full".into(),
                retry_after_ms: 250,
            }
        })
    }

    /// Resolve, validate live status, and produce a fresh signed descriptor.
    pub async fn connect(
        &self,
        unique_id: &str,
        customer_id: &str,
        request_id: u64,
    ) -> Result<ConnectResponse, ApiError> {
        let unique_id = normalize_unique_id(unique_id)?;
        let started = Instant::now();
        self.metrics
            .connect_requests_total
            .fetch_add(1, Ordering::Relaxed);

        let resolution = match self.resolve(&unique_id, request_id).await {
            Ok(resolution) => resolution,
            Err(error) => {
                self.record_error(&error);
                self.metrics
                    .connect_latency_seconds
                    .observe(started.elapsed());
                return Err(error);
            }
        };

        match resolution {
            RoomResolution::Offline { .. } => {
                self.metrics
                    .connect_offline_total
                    .fetch_add(1, Ordering::Relaxed);
                self.metrics
                    .connect_latency_seconds
                    .observe(started.elapsed());
                info!(request_id, customer_id, unique_id, "creator is offline");
                Ok(ConnectResponse::offline(unique_id.to_owned()))
            }
            RoomResolution::NotFound => {
                let error = ApiError::UserNotFound;
                self.record_error(&error);
                self.metrics
                    .connect_latency_seconds
                    .observe(started.elapsed());
                Err(error)
            }
            RoomResolution::Live { room_id, .. } => {
                let descriptor = match self.sign(&room_id, request_id).await {
                    Ok(descriptor) => descriptor,
                    Err(error) => {
                        // A refusal commonly means a room just ended or moved. Do not retain the
                        // live result through the next reconnect ticket request.
                        if matches!(error, ApiError::TikTokRefused(_)) {
                            self.cache.invalidate(&unique_id).await;
                        }
                        self.record_error(&error);
                        self.metrics
                            .connect_latency_seconds
                            .observe(started.elapsed());
                        return Err(error);
                    }
                };
                self.metrics
                    .connect_success_total
                    .fetch_add(1, Ordering::Relaxed);
                self.metrics
                    .connect_latency_seconds
                    .observe(started.elapsed());
                info!(
                    request_id,
                    customer_id, unique_id, room_id, "connection descriptor issued"
                );
                Ok(ConnectResponse::live(
                    unique_id.to_owned(),
                    room_id,
                    descriptor.url,
                    descriptor.cookies,
                    descriptor.user_agent,
                ))
            }
        }
    }

    async fn resolve(&self, unique_id: &str, request_id: u64) -> Result<RoomResolution, ApiError> {
        if let Some(value) = self.cache.get(unique_id).await {
            self.metrics
                .discovery_cache_hits_total
                .fetch_add(1, Ordering::Relaxed);
            debug!(request_id, unique_id, cache_hit = true, "room cache hit");
            return value_to_result(value);
        }
        self.metrics
            .discovery_cache_misses_total
            .fetch_add(1, Ordering::Relaxed);

        let flight = {
            let mut flights = self.flights.lock().await;
            flights
                .entry(unique_id.to_owned())
                .or_insert_with(|| Arc::new(OnceCell::new()))
                .clone()
        };

        let result = flight
            .get_or_init(|| async {
                let _slot = self
                    .discovery_slots
                    .clone()
                    .try_acquire_owned()
                    .map_err(|_| ResolveFailure::Capacity)?;
                self.metrics
                    .discovery_requests_total
                    .fetch_add(1, Ordering::Relaxed);
                let started = Instant::now();
                let result = match tokio::time::timeout(
                    self.config.request_timeout,
                    self.resolver.resolve(unique_id.to_owned()),
                )
                .await
                {
                    Ok(result) => result,
                    Err(_) => Err(ResolveFailure::Timeout),
                };
                self.metrics
                    .discovery_latency_seconds
                    .observe(started.elapsed());
                if let Ok(value) = &result {
                    self.cache.put(unique_id.to_owned(), value.clone()).await;
                }
                result
            })
            .await
            .clone();

        // Completed flights are removed, but callers already holding the Arc can finish reading
        // their shared result. The pointer check prevents a late cleanup from deleting a newer
        // flight for the same creator.
        let mut flights = self.flights.lock().await;
        if flights
            .get(unique_id)
            .is_some_and(|current| Arc::ptr_eq(current, &flight))
        {
            flights.remove(unique_id);
        }
        drop(flights);

        match result {
            Ok(RoomResolution::NotFound) => Err(ApiError::UserNotFound),
            Ok(value) => Ok(value),
            Err(error) => Err(error.into()),
        }
    }

    async fn sign(
        &self,
        room_id: &str,
        request_id: u64,
    ) -> Result<super::signer::SignedConnection, ApiError> {
        if !self.signer.ready() {
            return Err(ApiError::SignerUnavailable(
                "the warm signer runtime is not ready".into(),
            ));
        }
        let _slot = self
            .signing_slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| {
                self.metrics
                    .rate_limit_rejections_total
                    .fetch_add(1, Ordering::Relaxed);
                ApiError::RateLimited {
                    message: "Signing capacity is currently full".into(),
                    retry_after_ms: 500,
                }
            })?;
        self.metrics
            .signer_in_flight
            .fetch_add(1, Ordering::Relaxed);
        self.metrics
            .sign_requests_total
            .fetch_add(1, Ordering::Relaxed);
        let started = Instant::now();
        let result = match tokio::time::timeout(
            self.config.request_timeout,
            self.signer.sign_room(room_id.to_owned()),
        )
        .await
        {
            Ok(result) => result.map_err(ApiError::from),
            Err(_) => Err(ApiError::SignFailed("signing timed out".into())),
        };
        self.metrics.sign_latency_seconds.observe(started.elapsed());
        self.metrics
            .signer_in_flight
            .fetch_sub(1, Ordering::Relaxed);
        if result.is_ok() {
            self.metrics
                .sign_success_total
                .fetch_add(1, Ordering::Relaxed);
        } else {
            self.metrics
                .sign_failure_total
                .fetch_add(1, Ordering::Relaxed);
        }
        if let Err(error) = &result {
            warn!(request_id, room_id, error = %error, "connection descriptor signing failed");
        }
        result
    }

    fn record_error(&self, error: &ApiError) {
        self.metrics
            .connect_errors_total
            .fetch_add(1, Ordering::Relaxed);
        if matches!(error, ApiError::TikTokRefused(_)) {
            self.metrics
                .tiktok_refusals_total
                .fetch_add(1, Ordering::Relaxed);
        }
    }
}

fn value_to_result(value: RoomResolution) -> Result<RoomResolution, ApiError> {
    match value {
        RoomResolution::NotFound => Err(ApiError::UserNotFound),
        other => Ok(other),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::service::signer::{ConnectionSigner, SignFailure, SignedConnection};
    use crate::service::ServiceFuture;
    use ttl_sign_core::ClientIdentity;

    struct MockResolver {
        calls: Arc<AtomicU64>,
        result: RoomResolution,
    }

    impl RoomResolver for MockResolver {
        fn resolve(
            &self,
            _unique_id: String,
        ) -> ServiceFuture<'_, Result<RoomResolution, ResolveFailure>> {
            self.calls.fetch_add(1, Ordering::Relaxed);
            let result = self.result.clone();
            Box::pin(async move { Ok(result) })
        }
    }

    struct MockSigner;

    impl ConnectionSigner for MockSigner {
        fn sign_room(
            &self,
            room_id: String,
        ) -> ServiceFuture<'_, Result<SignedConnection, SignFailure>> {
            Box::pin(async move {
                Ok(SignedConnection {
                    url: format!("wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id={room_id}&X-Gnarly=test"),
                    cookies: "ttwid=test".into(),
                    user_agent: "fixture-agent".into(),
                })
            })
        }

        fn ready(&self) -> bool {
            true
        }
        fn identity(&self) -> ClientIdentity {
            ClientIdentity::new("fixture-agent")
        }
    }

    fn service(calls: Arc<AtomicU64>) -> Arc<ConnectService> {
        let config = AppConfig {
            connect_requests_per_minute: 10_000,
            unique_creators_per_minute: 10_000,
            ..AppConfig::default()
        };
        ConnectService::new(
            Arc::new(MockResolver {
                calls,
                result: RoomResolution::Live {
                    room_id: "7300".into(),
                    status: 2,
                    nickname: "Creator".into(),
                },
            }),
            Arc::new(MockSigner),
            config,
        )
    }

    #[test]
    fn normalization_accepts_both_public_spellings() {
        assert_eq!(normalize_unique_id(" creator ").unwrap(), "creator");
        assert_eq!(normalize_unique_id("@creator").unwrap(), "creator");
        assert!(normalize_unique_id("https://tiktok.com/@creator").is_err());
        assert!(normalize_unique_id("@creator/name").is_err());
    }

    #[tokio::test]
    async fn concurrent_same_creator_requests_single_flight_discovery() {
        let calls = Arc::new(AtomicU64::new(0));
        let service = service(Arc::clone(&calls));
        let mut tasks = Vec::new();
        for _ in 0..32 {
            let service = Arc::clone(&service);
            tasks.push(tokio::spawn(async move {
                service.connect("creator", "anonymous", 1).await.unwrap()
            }));
        }
        for task in tasks {
            let response = task.await.unwrap();
            assert_eq!(response.status, ConnectStatus::Live);
        }
        assert_eq!(calls.load(Ordering::Relaxed), 1);
    }

    #[tokio::test]
    async fn offline_is_a_success_response() {
        let calls = Arc::new(AtomicU64::new(0));
        let service = ConnectService::new(
            Arc::new(MockResolver {
                calls,
                result: RoomResolution::Offline {
                    room_id: Some("7300".into()),
                    status: 4,
                    nickname: "Creator".into(),
                },
            }),
            Arc::new(MockSigner),
            AppConfig::default(),
        );
        let response = service.connect("creator", "anonymous", 1).await.unwrap();
        assert_eq!(response.status, ConnectStatus::Offline);
        assert!(response.connection.is_none());
    }
}
