use ttl_live_discovery::{DiscoveryClient, DiscoveryError};

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
            match self.client.room_lookup(&unique_id).await {
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
                // The room lookup endpoint uses an absent `data.user`/empty response for an
                // unknown handle. Cache that classification briefly so a typo or deleted user
                // cannot trigger an upstream request on every client retry.
                Err(DiscoveryError::Decode(_)) | Err(DiscoveryError::EmptyResponse) => {
                    Ok(RoomResolution::NotFound)
                }
                Err(DiscoveryError::TooLarge(limit)) => Err(ResolveFailure::Decode(format!(
                    "response exceeded the {limit} byte limit"
                ))),
                Err(DiscoveryError::Signer(message)) => Err(ResolveFailure::Transport(message)),
                Err(DiscoveryError::Refused(message)) => Err(ResolveFailure::Refused(message)),
            }
        })
    }
}

fn usable_room_id(value: &str) -> Option<String> {
    ttl_sign_core::room::is_usable_room_id(value).then(|| value.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use ttl_sign_core::room::RoomLookup;

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
