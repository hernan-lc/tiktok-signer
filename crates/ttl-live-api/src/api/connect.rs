use axum::body::{to_bytes, Body};
use axum::extract::State;
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;
use serde_json::json;
use std::sync::Arc;

use crate::error::{ApiError, ApiErrorEnvelope};
use crate::service::{normalize_unique_id, ConnectResponse, ConnectService};

const MAX_CONNECT_BODY_BYTES: usize = 16 * 1024;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ConnectRequest {
    pub unique_id: String,
}

pub async fn connect(
    State(service): State<Arc<ConnectService>>,
    headers: HeaderMap,
    body: Body,
) -> Response {
    let request_id = service.next_request_id();

    let customer_id = match service.authenticate(headers.get(header::AUTHORIZATION)) {
        Ok(customer_id) => customer_id,
        Err(error) => return error_response(error, request_id),
    };
    let body = match to_bytes(body, MAX_CONNECT_BODY_BYTES).await {
        Ok(body) => body,
        Err(_) => return error_response(ApiError::InvalidUniqueId, request_id),
    };
    let request: ConnectRequest = match serde_json::from_slice(&body) {
        Ok(request) => request,
        Err(_) => return error_response(ApiError::InvalidUniqueId, request_id),
    };
    let unique_id = match normalize_unique_id(&request.unique_id) {
        Ok(unique_id) => unique_id,
        Err(error) => return error_response(error, request_id),
    };

    if let Err(error) = service.check_rate_limit(&customer_id, &unique_id).await {
        return error_response(error, request_id);
    }
    let _request_slot = match service.try_request_slot() {
        Ok(slot) => slot,
        Err(error) => return error_response(error, request_id),
    };

    match service.connect(&unique_id, &customer_id, request_id).await {
        Ok(response) => connect_response(response, request_id),
        Err(error) => error_response(error, request_id),
    }
}

pub async fn healthz() -> Response {
    (StatusCode::OK, Json(json!({ "status": "ok" }))).into_response()
}

pub async fn readyz(State(service): State<Arc<ConnectService>>) -> Response {
    if service.signer_ready() {
        return (StatusCode::OK, Json(json!({ "status": "ok" }))).into_response();
    }
    error_response(
        ApiError::SignerUnavailable("the warm signer runtime is not ready".into()),
        service.next_request_id(),
    )
}

pub async fn metrics(State(service): State<Arc<ConnectService>>) -> Response {
    let body = service.metrics().render();
    let mut response = body.into_response();
    response.headers_mut().insert(
        header::CONTENT_TYPE,
        HeaderValue::from_static("text/plain; version=0.0.4; charset=utf-8"),
    );
    response
}

fn connect_response(response: ConnectResponse, request_id: u64) -> Response {
    let mut response = (StatusCode::OK, Json(response)).into_response();
    add_request_id(response.headers_mut(), request_id);
    response
}

pub(crate) fn error_response(error: ApiError, request_id: u64) -> Response {
    let status = error.status();
    let retry_after_ms = error.retry_after_ms();
    let body: ApiErrorEnvelope = error.body();
    let mut response = (status, Json(body)).into_response();
    add_request_id(response.headers_mut(), request_id);
    if let Some(retry_after_ms) = retry_after_ms {
        // RFC 9110 defines Retry-After as seconds for an HTTP-date/delay value. The JSON contract
        // retains millisecond precision for SDK timers; the header uses a conservative ceil.
        let seconds = retry_after_ms.saturating_add(999) / 1_000;
        if let Ok(value) = HeaderValue::from_str(&seconds.max(1).to_string()) {
            response.headers_mut().insert(header::RETRY_AFTER, value);
        }
    }
    response
}

fn add_request_id(headers: &mut HeaderMap, request_id: u64) {
    if let Ok(value) = HeaderValue::from_str(&request_id.to_string()) {
        headers.insert("x-request-id", value);
    }
}
