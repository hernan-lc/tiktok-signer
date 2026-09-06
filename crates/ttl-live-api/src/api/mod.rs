//! HTTP routes for the connection-ticket contract.

mod connect;
mod relay;

pub use connect::{connect, healthz, metrics, readyz};
pub use relay::live;

use std::sync::Arc;

use axum::body::Body;
use axum::http::{header, HeaderMap, HeaderValue, Method, Request, StatusCode};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::Router;

use crate::service::ConnectService;

pub fn router(service: Arc<ConnectService>) -> Router {
    Router::new()
        .route("/v1/connect", post(connect))
        .route("/v1/live", get(live))
        .route("/healthz", get(healthz))
        .route("/readyz", get(readyz))
        .route("/metrics", get(metrics))
        .layer(middleware::from_fn(cors))
        .with_state(service)
}

/// Permissive CORS for local development clients.
///
/// The browser check page (`examples/live-sdk-bun/index.html`, served by `bun ./index.html`)
/// fetches `/v1/connect` cross-origin (Bun dev server on :3000, broker on :8080), so the broker
/// answers preflights and stamps every response. The broker binds loopback by default; do not
/// expose an unprotected broker with these headers to the public internet.
async fn cors(request: Request<Body>, next: Next) -> Response {
    if request.method() == Method::OPTIONS {
        return preflight();
    }
    let mut response = next.run(request).await;
    allow_headers(response.headers_mut());
    response
}

fn preflight() -> Response {
    let mut response = (StatusCode::NO_CONTENT, "").into_response();
    allow_headers(response.headers_mut());
    response
}

fn allow_headers(headers: &mut HeaderMap) {
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_ORIGIN,
        HeaderValue::from_static("*"),
    );
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_METHODS,
        HeaderValue::from_static("GET, POST, OPTIONS"),
    );
    headers.insert(
        header::ACCESS_CONTROL_ALLOW_HEADERS,
        HeaderValue::from_static("authorization, content-type, x-request-id"),
    );
    headers.insert(
        header::ACCESS_CONTROL_MAX_AGE,
        HeaderValue::from_static("86400"),
    );
}
