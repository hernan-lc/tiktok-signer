//! Connection-ticket service and adapters around existing repository components.

mod connect;
pub mod resolver;
pub mod signer;

pub use connect::{
    normalize_unique_id, ConnectResponse, ConnectService, ConnectStatus, ConnectionDescriptor,
    RoomResolution,
};
pub use resolver::{DiscoveryResolver, ResolveFailure, RoomResolver};
pub use signer::{BackendSigner, ConnectionSigner, SignFailure, SignedConnection};

use std::future::Future;
use std::pin::Pin;

/// Object-safe async result used by resolver and signer adapters.
pub type ServiceFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;
