use std::collections::HashMap;
use std::sync::Arc;

use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use tower::ServiceExt;
use ttl_live_api::service::{
    ConnectionSigner, ResolveFailure, RoomResolution, RoomResolver, ServiceFuture, SignFailure,
    SignedConnection,
};
use ttl_live_api::{router, AppConfig, ConnectService};
use ttl_sign_core::ClientIdentity;

struct Resolver {
    value: RoomResolution,
}

impl RoomResolver for Resolver {
    fn resolve(
        &self,
        _unique_id: String,
    ) -> ServiceFuture<'_, Result<RoomResolution, ResolveFailure>> {
        let value = self.value.clone();
        Box::pin(async move { Ok(value) })
    }
}

struct Signer;

impl ConnectionSigner for Signer {
    fn sign_room(
        &self,
        room_id: String,
    ) -> ServiceFuture<'_, Result<SignedConnection, SignFailure>> {
        Box::pin(async move {
            Ok(SignedConnection {
                url: format!("wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id={room_id}&X-Gnarly=fixture"),
                cookies: "ttwid=fixture".into(),
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

async fn request(
    app: axum::Router,
    method: &str,
    uri: &str,
    body: &str,
) -> axum::response::Response {
    app.oneshot(
        Request::builder()
            .method(method)
            .uri(uri)
            .header("content-type", "application/json")
            .body(Body::from(body.to_owned()))
            .unwrap(),
    )
    .await
    .unwrap()
}

fn live_service() -> Arc<ConnectService> {
    let config = AppConfig {
        connect_requests_per_minute: 10_000,
        unique_creators_per_minute: 10_000,
        ..AppConfig::default()
    };
    ConnectService::new(
        Arc::new(Resolver {
            value: RoomResolution::Live {
                room_id: "7300".into(),
                status: 2,
                nickname: "Creator".into(),
            },
        }),
        Arc::new(Signer),
        config,
    )
}

fn offline_service() -> Arc<ConnectService> {
    let config = AppConfig {
        connect_requests_per_minute: 10_000,
        unique_creators_per_minute: 10_000,
        ..AppConfig::default()
    };
    ConnectService::new(
        Arc::new(Resolver {
            value: RoomResolution::Offline {
                room_id: None,
                status: 0,
                nickname: String::new(),
            },
        }),
        Arc::new(Signer),
        config,
    )
}

async fn get(app: axum::Router, uri: &str) -> axum::response::Response {
    app.oneshot(Request::builder().uri(uri).body(Body::empty()).unwrap())
        .await
        .unwrap()
}

#[tokio::test]
async fn relay_offline_answers_json_without_an_upgrade() {
    let response = get(router(offline_service()), "/v1/live?uniqueId=creator").await;
    assert_eq!(response.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["uniqueId"], "creator");
    assert_eq!(body["status"], "offline");
}

#[tokio::test]
async fn relay_rejects_invalid_unique_ids_with_the_common_error_shape() {
    let response = get(router(live_service()), "/v1/live?uniqueId=@@nope").await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["error"]["code"], "INVALID_UNIQUE_ID");
}

#[tokio::test]
async fn relay_live_without_upgrade_headers_demands_an_upgrade() {
    let response = get(router(live_service()), "/v1/live?uniqueId=creator").await;
    assert_eq!(response.status(), StatusCode::UPGRADE_REQUIRED);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["error"]["code"], "UPGRADE_REQUIRED");
}

#[tokio::test]
async fn live_contract_accepts_only_unique_id_and_returns_descriptor() {
    let response = request(
        router(live_service()),
        "POST",
        "/v1/connect",
        r#"{"uniqueId":"@creator"}"#,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["uniqueId"], "creator");
    assert_eq!(body["roomId"], "7300");
    assert_eq!(body["status"], "live");
    assert!(body["connection"]["url"]
        .as_str()
        .unwrap()
        .starts_with("wss://"));
}

#[tokio::test]
async fn browser_preflight_is_answered_with_allow_headers() {
    let response = request(router(live_service()), "OPTIONS", "/v1/connect", "")
        .await;
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let headers = response.headers();
    assert_eq!(
        headers.get("access-control-allow-origin").map(|v| v.to_str().unwrap()),
        Some("*"),
    );
    assert!(headers.contains_key("access-control-allow-methods"));
    assert!(headers.contains_key("access-control-allow-headers"));
}

#[tokio::test]
async fn connect_responses_carry_cors_headers_for_browser_clients() {
    let response = request(
        router(live_service()),
        "POST",
        "/v1/connect",
        r#"{"uniqueId":"@creator"}"#,
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(
        response
            .headers()
            .get("access-control-allow-origin")
            .map(|v| v.to_str().unwrap()),
        Some("*"),
    );
}

#[tokio::test]
async fn extra_public_inputs_are_rejected_with_the_common_error_shape() {
    let response = request(
        router(live_service()),
        "POST",
        "/v1/connect",
        r#"{"uniqueId":"creator","roomId":"7300"}"#,
    )
    .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["error"]["code"], "INVALID_UNIQUE_ID");
    assert_eq!(body["error"]["retryable"], false);
}

#[tokio::test]
async fn oversized_bodies_are_rejected_without_buffering_an_unbounded_request() {
    let response = request(
        router(live_service()),
        "POST",
        "/v1/connect",
        &format!("{{\"uniqueId\":\"{}\"}}", "a".repeat(20_000)),
    )
    .await;
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let body: serde_json::Value =
        serde_json::from_slice(&to_bytes(response.into_body(), usize::MAX).await.unwrap()).unwrap();
    assert_eq!(body["error"]["code"], "INVALID_UNIQUE_ID");
}

#[tokio::test]
async fn configured_api_keys_are_enforced_and_retry_after_is_serialized() {
    let config = AppConfig {
        api_keys: HashMap::from([(String::from("secret"), String::from("acme"))]),
        connect_requests_per_minute: 1,
        unique_creators_per_minute: 10,
        ..AppConfig::default()
    };
    let service = ConnectService::new(
        Arc::new(Resolver {
            value: RoomResolution::Offline {
                room_id: None,
                status: 0,
                nickname: String::new(),
            },
        }),
        Arc::new(Signer),
        config,
    );
    let app = router(service);
    let missing = request(
        app.clone(),
        "POST",
        "/v1/connect",
        r#"{"uniqueId":"creator"}"#,
    )
    .await;
    assert_eq!(missing.status(), StatusCode::UNAUTHORIZED);

    let first = Request::builder()
        .method("POST")
        .uri("/v1/connect")
        .header("authorization", "Bearer secret")
        .body(Body::from(r#"{"uniqueId":"creator"}"#))
        .unwrap();
    let first = app.clone().oneshot(first).await.unwrap();
    assert_eq!(first.status(), StatusCode::OK);
    let second = Request::builder()
        .method("POST")
        .uri("/v1/connect")
        .header("authorization", "Bearer secret")
        .body(Body::from(r#"{"uniqueId":"creator2"}"#))
        .unwrap();
    let second = app.oneshot(second).await.unwrap();
    assert_eq!(second.status(), StatusCode::TOO_MANY_REQUESTS);
    assert!(second.headers().get("retry-after").is_some());
}
