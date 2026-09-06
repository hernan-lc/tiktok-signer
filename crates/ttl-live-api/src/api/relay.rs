//! Browser WebSocket relay (`GET /v1/live?uniqueId=<handle>`).
//!
//! A page `WebSocket` cannot set `Cookie`/`User-Agent` headers, and TikTok refuses the
//! jar-less handshake (measured upstream as an immediate 1006 — the same failure a direct
//! browser socket shows). The relay therefore dials TikTok with the ticket headers and pipes
//! raw frames both ways. The browser still owns the protocol: it sends `enter-room`,
//! heartbeats and ACKs, and decodes events — the broker never interprets the stream, it only
//! forwards bytes. An old signed URL is never reused: each relay resolves a fresh ticket,
//! mirroring SDK reconnect semantics (re-sign on every new handshake).
//!
//! Only `uniqueId` (and an optional `apiKey` query alias, since a page socket cannot send an
//! `Authorization` header either) enters here. Query keys are tolerated for a local check
//! tool only: secrets in URLs surface in DevTools, proxy/CDN access logs, tracing, and
//! pasted URLs. Cookies, signatures and keys never reach logs.

use std::sync::Arc;

use axum::body::Body;
use axum::extract::ws::{CloseFrame as BrowserClose, Message as BrowserMessage, WebSocket};
use axum::extract::{FromRequest, State};
use axum::http::{header, HeaderMap, HeaderValue, Request, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use futures_util::{SinkExt, StreamExt};
use serde::Deserialize;
use serde_json::json;
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::CloseFrame as TiktokClose;
use tokio_tungstenite::tungstenite::Message as TiktokMessage;
use tracing::{info, warn};

use super::connect::{connect_response, error_response};
use crate::error::ApiError;
use crate::service::{normalize_unique_id, ConnectService, ConnectStatus, ConnectionDescriptor};

/// Query input. `uniqueId` keeps its POST-contract spelling; `apiKey` exists because a page
/// socket cannot send headers (see the module docs for why query secrets stay local-only).
#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LiveParams {
    #[serde(default, alias = "unique_id", alias = "handle", alias = "id")]
    pub unique_id: Option<String>,
    #[serde(default, alias = "api_key")]
    pub api_key: Option<String>,
}

/// `GET /v1/live?uniqueId=<handle>`.
///
/// The ticket is resolved before any upgrade: offline creators and failures answer with the
/// same JSON contract as `POST /v1/connect`, and only a live descriptor proceeds to the
/// WebSocket handshake. Taking the raw request (instead of a `WebSocketUpgrade` extractor)
/// keeps those JSON answers reachable for plain HTTP clients and tests.
pub async fn live(
    State(service): State<Arc<ConnectService>>,
    request: Request<Body>,
) -> Response {
    live_inner(service, request).await
}

async fn live_inner(service: Arc<ConnectService>, request: Request<Body>) -> Response {
    let request_id = service.next_request_id();

    let params: LiveParams = match request.uri().query() {
        Some(query) => match serde_urlencoded::from_str(query) {
            Ok(params) => params,
            Err(_) => return error_response(ApiError::InvalidUniqueId, request_id),
        },
        None => return error_response(ApiError::InvalidUniqueId, request_id),
    };
    let unique_id = match params.unique_id.as_deref().map(normalize_unique_id) {
        Some(Ok(unique_id)) => unique_id,
        _ => return error_response(ApiError::InvalidUniqueId, request_id),
    };

    let customer_id = match service.authenticate(authorization(request.headers(), params.api_key).as_ref()) {
        Ok(customer_id) => customer_id,
        Err(error) => return error_response(error, request_id),
    };
    if let Err(error) = service.check_rate_limit(&customer_id, &unique_id).await {
        return error_response(error, request_id);
    }
    // Bounds relay *setup* (resolve/sign/dial), not live sockets: the permit drops when
    // this handler returns the upgrade response, while the relay(...) task stays up.
    // Deliberate for a local check tool; an Internet-facing endpoint would need a
    // separate cap on concurrent relays.
    let _request_slot = match service.try_request_slot() {
        Ok(slot) => slot,
        Err(error) => return error_response(error, request_id),
    };

    let ticket = match service.connect(&unique_id, &customer_id, request_id).await {
        Ok(ticket) => ticket,
        Err(error) => return error_response(error, request_id),
    };
    let (room_id, descriptor) = match ticket.status {
        ConnectStatus::Live => match ticket.connection {
            Some(descriptor) => (ticket.room_id.unwrap_or_default(), descriptor),
            None => {
                return error_response(
                    ApiError::Internal("live ticket is missing its descriptor".into()),
                    request_id,
                )
            }
        },
        ConnectStatus::Offline => return connect_response(ticket, request_id),
    };

    if !is_upgrade(request.headers()) {
        let mut response = (
            StatusCode::UPGRADE_REQUIRED,
            Json(json!({"error": {"code": "UPGRADE_REQUIRED",
                "message": "GET /v1/live needs a WebSocket client",
                "retryable": false}})),
        )
            .into_response();
        response
            .headers_mut()
            .insert("x-request-id", request_id_header(request_id));
        return response;
    }

    let upgrade = match axum::extract::WebSocketUpgrade::from_request(request, &()).await {
        Ok(upgrade) => upgrade,
        Err(error) => return error.into_response(),
    };
    let dial = match tiktok_request(&descriptor).inspect_err(|_| {
        warn!(request_id, unique_id, "relay descriptor invalid");
    }) {
        Ok(dial) => dial,
        Err(error) => return error_response(error, request_id),
    };
    let (tiktok, _) = match tokio_tungstenite::connect_async(dial).await {
        Ok(pair) => pair,
        Err(error) => {
            warn!(request_id, unique_id, kind = "tiktok_dial", detail = %short(error.to_string()), "relay dial failed");
            return error_response(
                ApiError::DiscoveryFailed("TikTok LIVE socket could not be opened".into()),
                request_id,
            );
        }
    };
    info!(request_id, unique_id, room_id, "relay open");
    let mut response = upgrade
        .on_upgrade(move |browser| relay(browser, tiktok, unique_id, room_id, request_id))
        .into_response();
    response
        .headers_mut()
        .insert("x-request-id", request_id_header(request_id));
    response
}

/// Forward raw frames until either side goes away, then drop both halves.
async fn relay(
    browser: WebSocket,
    tiktok: tokio_tungstenite::WebSocketStream<
        tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
    >,
    unique_id: String,
    room_id: String,
    request_id: u64,
) {
    let (mut browser_tx, mut browser_rx) = browser.split();
    let (mut tiktok_tx, mut tiktok_rx) = tiktok.split();

    let up = async {
        while let Some(message) = browser_rx.next().await {
            match message {
                Ok(message) => {
                    if let Some(message) = to_tiktok(message) {
                        if tiktok_tx.send(message).await.is_err() {
                            break;
                        }
                    }
                }
                Err(_) => break,
            }
        }
    };
    let down = async {
        while let Some(message) = tiktok_rx.next().await {
            match message {
                Ok(message) => {
                    if let Some(message) = to_browser(message) {
                        if browser_tx.send(message).await.is_err() {
                            break;
                        }
                    }
                }
                Err(_) => break,
            }
        }
    };
    tokio::select! {
        _ = up => {},
        _ = down => {},
    }
    info!(request_id, unique_id, room_id, "relay closed");
}

/// `Authorization` header first (non-browser clients), else the `apiKey` query alias.
/// Values stay in memory; they are never logged.
fn authorization(headers: &HeaderMap, api_key: Option<String>) -> Option<HeaderValue> {
    if let Some(header) = headers.get(header::AUTHORIZATION) {
        return Some(header.clone());
    }
    api_key.and_then(|key| format!("Bearer {key}").parse().ok())
}

/// Upgrade intent without consuming the request.
fn is_upgrade(headers: &HeaderMap) -> bool {
    let upgrade = headers
        .get(header::UPGRADE)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.eq_ignore_ascii_case("websocket"))
        .unwrap_or(false);
    let connection = headers
        .get(header::CONNECTION)
        .and_then(|value| value.to_str().ok())
        .map(|value| {
            value
                .split(',')
                .any(|token| token.trim().eq_ignore_ascii_case("upgrade"))
        })
        .unwrap_or(false);
    upgrade && connection
}

fn request_id_header(request_id: u64) -> HeaderValue {
    HeaderValue::from_str(&request_id.to_string())
        .unwrap_or_else(|_| HeaderValue::from_static("0"))
}

/// The TikTok leg of the relay, with the ticket headers a browser could never send.
/// Only `ws(s)` descriptors are dialed; the URL itself comes from our own signer.
///
/// The signed query carries raw spaces, so the request is built the way the proven
/// `ttl-live-ws` client builds it: tungstenite's client-request conversion (WHATWG URL
/// parsing, which encodes what the wire needs) instead of a strict `http::Uri`.
pub(crate) fn tiktok_request(descriptor: &ConnectionDescriptor) -> Result<Request<()>, ApiError> {
    use tokio_tungstenite::tungstenite::client::IntoClientRequest;

    if !descriptor.url.starts_with("wss://") && !descriptor.url.starts_with("ws://") {
        return Err(ApiError::Internal("live descriptor has an unsupported scheme".into()));
    }
    let invalid = || ApiError::Internal("live descriptor headers are invalid".into());
    // The signed query carries raw spaces (the signature covers the unencoded bytes and
    // servers percent-decode before verifying — the Node `ws` path relies on the same).
    // Encode them for the wire; everything else passes through byte-identical.
    let normalized = descriptor.url.replace(' ', "%20");
    let mut request = normalized
        .as_str()
        .into_client_request()
        .map_err(|_| ApiError::Internal("live descriptor URL is invalid".into()))?;
    request.headers_mut().insert(
        header::COOKIE,
        HeaderValue::from_str(&descriptor.cookies).map_err(|_| invalid())?,
    );
    request.headers_mut().insert(
        header::USER_AGENT,
        HeaderValue::from_str(&descriptor.user_agent).map_err(|_| invalid())?,
    );
    request.headers_mut().insert(
        header::ORIGIN,
        HeaderValue::from_static("https://www.tiktok.com"),
    );
    Ok(request)
}

pub(crate) fn to_tiktok(message: BrowserMessage) -> Option<TiktokMessage> {
    match message {
        BrowserMessage::Text(text) => Some(TiktokMessage::Text(text.to_string().into())),
        BrowserMessage::Binary(bytes) => Some(TiktokMessage::Binary(bytes.to_vec().into())),
        BrowserMessage::Ping(bytes) => Some(TiktokMessage::Ping(bytes.to_vec().into())),
        BrowserMessage::Pong(bytes) => Some(TiktokMessage::Pong(bytes.to_vec().into())),
        BrowserMessage::Close(frame) => Some(TiktokMessage::Close(frame.map(|frame| TiktokClose {
            code: CloseCode::from(frame.code),
            reason: frame.reason.to_string().into(),
        }))),
    }
}

pub(crate) fn to_browser(message: TiktokMessage) -> Option<BrowserMessage> {
    match message {
        TiktokMessage::Text(text) => Some(BrowserMessage::Text(text.to_string().into())),
        TiktokMessage::Binary(bytes) => Some(BrowserMessage::Binary(bytes.to_vec().into())),
        TiktokMessage::Ping(bytes) => Some(BrowserMessage::Ping(bytes.to_vec().into())),
        TiktokMessage::Pong(bytes) => Some(BrowserMessage::Pong(bytes.to_vec().into())),
        TiktokMessage::Close(frame) => Some(BrowserMessage::Close(frame.map(|frame| BrowserClose {
            code: u16::from(frame.code),
            reason: frame.reason.to_string().into(),
        }))),
        TiktokMessage::Frame(_) => None,
    }
}

/// Truncate an upstream transport error for logs; bodies stay out of structured fields.
fn short(mut detail: String) -> String {
    detail.truncate(160);
    detail
}

#[cfg(test)]
mod tests {
    use super::*;

    fn descriptor() -> ConnectionDescriptor {
        ConnectionDescriptor {
            url: "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300&X-Gnarly=fixture".into(),
            cookies: "ttwid=fixture".into(),
            user_agent: "fixture-agent".into(),
        }
    }

    #[test]
    fn relay_dial_carries_the_ticket_headers_a_browser_cannot_send() {
        let request = tiktok_request(&descriptor()).unwrap();
        assert_eq!(request.uri().host(), Some("webcast-ws.tiktok.com"));
        assert_eq!(
            request.headers().get(header::COOKIE).map(|v| v.to_str().unwrap()),
            Some("ttwid=fixture")
        );
        assert_eq!(
            request.headers().get(header::USER_AGENT).map(|v| v.to_str().unwrap()),
            Some("fixture-agent")
        );
        assert_eq!(
            request.headers().get(header::ORIGIN).map(|v| v.to_str().unwrap()),
            Some("https://www.tiktok.com")
        );
    }

    #[test]
    fn signed_urls_with_raw_spaces_are_wire_encoded_not_rejected() {
        // Real tickets carry unencoded spaces in the signed query; the strict
        // `http::Uri` parser refuses those, so the dial path must encode them.
        let mut descriptor = descriptor();
        descriptor.url = "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=7300&browser_platform=Linux x86_64&X-Gnarly=fixture".into();
        let request = tiktok_request(&descriptor).unwrap();
        assert_eq!(request.uri().host(), Some("webcast-ws.tiktok.com"));
        assert!(!request.uri().to_string().contains(' '));
    }

    #[test]
    fn non_socket_descriptors_are_never_dialed() {
        let mut descriptor = descriptor();
        descriptor.url = "https://webcast-ws.tiktok.com/im/fetch/".into();
        assert!(tiktok_request(&descriptor).is_err());
    }

    #[test]
    fn frame_mapping_preserves_binary_text_and_close() {
        let binary = BrowserMessage::Binary(vec![1u8, 2, 3].into());
        assert!(matches!(to_tiktok(binary), Some(TiktokMessage::Binary(_))));
        let text = TiktokMessage::Text("hi".into());
        match to_browser(text) {
            Some(BrowserMessage::Text(echoed)) => assert_eq!(echoed.as_str(), "hi"),
            other => panic!("expected text, got {other:?}"),
        }
        let close = BrowserMessage::Close(Some(BrowserClose { code: 1000, reason: "done".into() }));
        match to_tiktok(close) {
            Some(TiktokMessage::Close(Some(frame))) => {
                assert_eq!(u16::from(frame.code), 1000);
            }
            other => panic!("expected close, got {other:?}"),
        }
        assert!(to_browser(TiktokMessage::Ping(vec![].into())).is_some());
    }

    #[test]
    fn query_bearer_is_accepted_when_no_header_is_present() {
        let headers = HeaderMap::new();
        let value = authorization(&headers, Some("secret".into())).unwrap();
        assert_eq!(value.to_str().unwrap(), "Bearer secret");
        assert!(authorization(&headers, None).is_none());
    }
}
