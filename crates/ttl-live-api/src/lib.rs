//! HTTP connection-ticket broker for the direct TikTok LIVE SDK.
//!
//! The broker has one deliberately narrow job: accept a validated `uniqueId`, resolve it to a
//! currently-live room, and return a freshly signed WebSocket descriptor. It never opens a
//! persistent TikTok LIVE socket and it never sees the event stream. SDK clients use the returned
//! descriptor to connect directly to TikTok.

pub mod api;
pub mod cache;
pub mod config;
pub mod error;
pub mod identity;
pub mod limiter;
pub mod metrics;
pub mod service;

pub use api::router;
pub use config::AppConfig;
pub use error::{ApiError, ApiErrorBody};
pub use service::{
    normalize_unique_id, ConnectResponse, ConnectService, ConnectStatus, ConnectionDescriptor,
    RoomResolution,
};
