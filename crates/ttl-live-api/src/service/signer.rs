use std::sync::Arc;

use ttl_sign_core::{
    ClientIdentity, RejectReason, SignError, SignOutcome, SignerBackend, TransportRequest,
};

use super::ServiceFuture;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignedConnection {
    pub url: String,
    pub cookies: String,
    pub user_agent: String,
}

#[derive(Debug, Clone, thiserror::Error)]
pub enum SignFailure {
    #[error("TikTok rejected the signing request: {0}")]
    Refused(String),
    #[error("signer is unavailable: {0}")]
    Unavailable(String),
    #[error("signing failed: {0}")]
    Failed(String),
}

/// Room-only signer capability. There is deliberately no method accepting a URL or query string.
pub trait ConnectionSigner: Send + Sync {
    fn sign_room(
        &self,
        room_id: String,
    ) -> ServiceFuture<'_, Result<SignedConnection, SignFailure>>;
    fn ready(&self) -> bool;
    fn identity(&self) -> ClientIdentity;
}

/// Adapter for the repository's existing warm `SignerBackend` implementations.
pub struct BackendSigner {
    backend: Arc<dyn SignerBackend>,
}

impl BackendSigner {
    pub fn new(backend: Arc<dyn SignerBackend>) -> Self {
        Self { backend }
    }

    pub fn backend(&self) -> &Arc<dyn SignerBackend> {
        &self.backend
    }
}

impl ConnectionSigner for BackendSigner {
    fn sign_room(
        &self,
        room_id: String,
    ) -> ServiceFuture<'_, Result<SignedConnection, SignFailure>> {
        Box::pin(async move {
            let requested_room_id = room_id.clone();
            match self.backend.transport(TransportRequest::new(room_id)).await {
                SignOutcome::Ok(signed) => {
                    validate_signed_socket_url(&signed.signed_url, &requested_room_id)?;
                    Ok(SignedConnection {
                        url: signed.signed_url,
                        cookies: signed.cookies.to_cookie_string(),
                        user_agent: signed.user_agent,
                    })
                }
                SignOutcome::Rejected(reason) => Err(SignFailure::Refused(reason.to_string())),
                SignOutcome::Transport(error) => Err(map_sign_error(error)),
            }
        })
    }

    fn ready(&self) -> bool {
        true
    }

    fn identity(&self) -> ClientIdentity {
        self.backend.identity()
    }
}

/// Defense in depth around the room-only signer capability. Even an internal signer adapter must
/// not accidentally turn this API into a generic URL signer if a backend implementation changes.
fn validate_signed_socket_url(url: &str, room_id: &str) -> Result<(), SignFailure> {
    if room_id.is_empty() || !room_id.chars().all(|character| character.is_ascii_digit()) {
        return Err(SignFailure::Failed(
            "signer was asked to sign an invalid room id".into(),
        ));
    }
    let Some(rest) = url.strip_prefix("wss://") else {
        return Err(SignFailure::Failed(
            "signer returned a non-WebSocket URL".into(),
        ));
    };
    let Some((host, path_and_query)) = rest.split_once('/') else {
        return Err(SignFailure::Failed(
            "signer returned an incomplete WebSocket URL".into(),
        ));
    };
    if !matches!(
        host,
        "webcast-ws.tiktok.com" | "webcast-ws.us.tiktok.com" | "webcast-ws.eu.tiktok.com"
    ) {
        return Err(SignFailure::Failed(
            "signer returned an unexpected WebSocket host".into(),
        ));
    }
    let Some((path, query)) = path_and_query.split_once('?') else {
        return Err(SignFailure::Failed(
            "signer returned a WebSocket URL without a query".into(),
        ));
    };
    if path != "webcast/im/ws_proxy/ws_reuse_supplement/" {
        return Err(SignFailure::Failed(
            "signer returned an unexpected WebSocket path".into(),
        ));
    }
    let room_matches = query.split('&').any(|pair| {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        key == "room_id" && value == room_id
    });
    let has_signature = query.split('&').any(|pair| {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        key == "X-Gnarly" && !value.is_empty()
    });
    if !room_matches || !has_signature {
        return Err(SignFailure::Failed(
            "signer returned a descriptor missing its room or signature".into(),
        ));
    }
    Ok(())
}

fn map_sign_error(error: SignError) -> SignFailure {
    match error {
        SignError::SdkNotReady
        | SignError::NoInstanceAvailable
        | SignError::BackendUnavailable(_)
        | SignError::EngineGone(_)
        | SignError::LoginTimeout(_) => SignFailure::Unavailable(error.to_string()),
        SignError::Refused(refusal) => SignFailure::Refused(refusal.to_string()),
        SignError::Timeout(_)
        | SignError::Transport(_)
        | SignError::Decode(_)
        | SignError::Bridge(_) => SignFailure::Failed(error.to_string()),
    }
}

#[allow(dead_code)]
fn _keep_reject_reason_documented(reason: RejectReason) -> String {
    reason.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ttl_sign_core::{BackendFuture, CookieJar, MockBackend, SignedFetch};

    #[tokio::test]
    async fn adapter_returns_only_the_descriptor_fields() {
        let backend = MockBackend::new(ClientIdentity::new("fixture-agent")).with_response(
            "1",
            SignOutcome::Ok(SignedFetch {
                protobuf: vec![],
                cookies: CookieJar::parse("ttwid=fixture"),
                user_agent: "fixture-agent".into(),
                signed_url: "wss://webcast-ws.tiktok.com/webcast/im/ws_proxy/ws_reuse_supplement/?room_id=1&X-Gnarly=x".into(),
            }),
        );
        let signer = BackendSigner::new(Arc::new(backend));
        let signed = signer.sign_room("1".into()).await.unwrap();
        assert_eq!(signed.cookies, "ttwid=fixture");
        assert!(signed.url.contains("X-Gnarly"));
    }

    #[tokio::test]
    async fn adapter_rejects_a_generic_or_mismatched_socket_descriptor() {
        let backend = MockBackend::new(ClientIdentity::new("fixture-agent")).with_response(
            "1",
            SignOutcome::Ok(SignedFetch {
                protobuf: vec![],
                cookies: CookieJar::parse("ttwid=fixture"),
                user_agent: "fixture-agent".into(),
                signed_url: "wss://example.invalid/anything?url=https://anything".into(),
            }),
        );
        let signer = BackendSigner::new(Arc::new(backend));
        assert!(matches!(
            signer.sign_room("1".into()).await,
            Err(SignFailure::Failed(_))
        ));
    }

    // Keep the trait's future bound visible to the compiler if this file is refactored.
    fn _future_is_send(_: BackendFuture<'_>) {}
}
