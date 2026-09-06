use ttl_live_discovery::{DiscoveryClient, DiscoveryError};
use ttl_sign_core::room::RoomLookup;

use super::{RoomResolution, ServiceFuture};

/// Discovery failures are reduced to transport-safe classes before reaching the HTTP layer.
#[derive(Debug, Clone, thiserror::Error)]
pub enum ResolveFailure {
    #[error("discovery capacity is currently full")]
    Capacity,
    #[error("discovery timed out")]
    Timeout,
    #[error("user not found: {0}")]
    NotFound(String),
    #[error("TikTok answered HTTP {status}: {message}")]
    Status { status: u16, message: String },
    #[error("discovery transport failed: {0}")]
    Transport(String),
    #[error("discovery response could not be decoded: {0}")]
    Decode(String),
    #[error("TikTok refused discovery: {0}")]
    Refused(String),
}

/// Resolves one canonical unique id to a live/offline/not-found result.
pub trait RoomResolver: Send + Sync {
    fn resolve(
        &self,
        unique_id: String,
    ) -> ServiceFuture<'_, Result<RoomResolution, ResolveFailure>>;
}

#[derive(Debug, Clone)]
pub struct DiscoveryResolver {
    client: DiscoveryClient,
}

impl DiscoveryResolver {
    pub fn new(client: DiscoveryClient) -> Self {
        Self { client }
    }

    pub fn client(&self) -> &DiscoveryClient {
        &self.client
    }
}

impl RoomResolver for DiscoveryResolver {
    fn resolve(
        &self,
        unique_id: String,
    ) -> ServiceFuture<'_, Result<RoomResolution, ResolveFailure>> {
        Box::pin(async move {
            let result = self.client.room_lookup(&unique_id).await;
            classify(&unique_id, result)
        })
    }
}

/// Reduce one lookup outcome to the broker's resolution contract.
///
/// The load-bearing distinction: an *absent user* (`UserNotFound`) is a cacheable
/// not-found, while an *unparseable body* (`Decode`) is a retryable upstream failure.
/// A TikTok schema change must surface as `DISCOVERY_FAILED`, never as `USER_NOT_FOUND`.
fn classify(
    _unique_id: &str,
    result: Result<RoomLookup, DiscoveryError>,
) -> Result<RoomResolution, ResolveFailure> {
    match result {
        Ok(room) if room.is_live() => Ok(RoomResolution::Live {
            room_id: room.room_id,
            status: room.status,
            nickname: room.nickname,
        }),
        Ok(room) => Ok(RoomResolution::Offline {
            room_id: usable_room_id(&room.room_id),
            status: room.status,
            nickname: room.nickname,
        }),
        // The unsigned endpoint uses room id 0/empty for a creator that has no current
        // room. Treat it as a normal offline result so SDK callers do not need to turn a
        // normal broadcast transition into exception-driven control flow.
        Err(DiscoveryError::NoRoom(_)) => Ok(RoomResolution::Offline {
            room_id: None,
            status: 0,
            nickname: String::new(),
        }),
        Err(DiscoveryError::Status { status }) => Err(ResolveFailure::Status {
            status,
            message: "room lookup failed".into(),
        }),
        Err(DiscoveryError::Transport(message)) => Err(ResolveFailure::Transport(message)),
        Err(DiscoveryError::Decode(message)) => Err(ResolveFailure::Decode(message)),
        // Valid JSON with no `data.user`: the handle does not resolve. Kept as a
        // cacheable not-found so a typo or deleted user cannot trigger an upstream
        // request on every client retry. Only this arm may produce `NotFound`.
        Err(DiscoveryError::UserNotFound(_)) => Ok(RoomResolution::NotFound),
        // Produced only by the signed request paths, never by room lookup. Mapped to a
        // retryable failure rather than a cached 404: if this ever fires here, the safe
        // classification is "upstream misbehaved", not "user does not exist".
        Err(DiscoveryError::EmptyResponse) => Err(ResolveFailure::Transport(
            "endpoint accepted the request but returned nothing".into(),
        )),
        Err(DiscoveryError::TooLarge(limit)) => Err(ResolveFailure::Decode(format!(
            "response exceeded the {limit} byte limit"
        ))),
        Err(DiscoveryError::Signer(message)) => Err(ResolveFailure::Transport(message)),
        Err(DiscoveryError::Refused(message)) => Err(ResolveFailure::Refused(message)),
    }
}

fn usable_room_id(value: &str) -> Option<String> {
    ttl_sign_core::room::is_usable_room_id(value).then(|| value.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ttl_sign_core::room::RoomLookup;

    fn live_room() -> RoomLookup {
        RoomLookup {
            unique_id: "creator".into(),
            room_id: "7300".into(),
            nickname: "Creator".into(),
            status: 2,
            title: String::new(),
        }
    }

    #[test]
    fn a_decode_failure_is_a_retryable_upstream_error_never_a_not_found() {
        let outcome = classify(
            "creator",
            Err(DiscoveryError::Decode("schema moved".into())),
        );
        assert!(matches!(outcome, Err(ResolveFailure::Decode(_))));
    }

    #[test]
    fn only_an_absent_user_may_resolve_to_not_found() {
        assert!(matches!(
            classify("creator", Err(DiscoveryError::UserNotFound("creator".into()))),
            Ok(RoomResolution::NotFound)
        ));
        // Every other failure shape stays an error, even the ones that used to share
        // the not-found arm.
        assert!(matches!(
            classify("creator", Err(DiscoveryError::EmptyResponse)),
            Err(ResolveFailure::Transport(_))
        ));
        assert!(matches!(
            classify("creator", Err(DiscoveryError::Transport("down".into()))),
            Err(ResolveFailure::Transport(_))
        ));
    }

    #[test]
    fn live_and_offline_rooms_classify_by_status() {
        assert!(matches!(
            classify("creator", Ok(live_room())),
            Ok(RoomResolution::Live { .. })
        ));
        let mut offline = live_room();
        offline.status = 4;
        assert!(matches!(
            classify("creator", Ok(offline)),
            Ok(RoomResolution::Offline { .. })
        ));
    }

    #[test]
    fn a_non_live_room_is_not_a_resolution_failure() {
        let room = RoomLookup {
            unique_id: "creator".into(),
            room_id: "123".into(),
            nickname: "Creator".into(),
            status: 4,
            title: String::new(),
        };
        assert!(!room.is_live());
        assert_eq!(usable_room_id(&room.room_id), Some("123".into()));
    }
}
