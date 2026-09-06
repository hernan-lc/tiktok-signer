//! HTTP routes for the connection-ticket contract.

mod connect;

pub use connect::{connect, healthz, metrics, readyz};

use std::sync::Arc;

use axum::routing::{get, post};
use axum::Router;

use crate::service::ConnectService;

pub fn router(service: Arc<ConnectService>) -> Router {
    Router::new()
        .route("/v1/connect", post(connect))
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .route("/metrics", get(metrics))
        .with_state(service)
}
