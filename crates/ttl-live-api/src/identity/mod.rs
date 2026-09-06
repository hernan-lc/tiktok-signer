//! Guest/session identity helpers used during broker startup.

mod guest;

pub use guest::{bootstrap_guest_identity, GuestIdentity, IdentityError};
