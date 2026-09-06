//! Public error contract for the broker.

use axum::http::StatusCode;
use serde::Serialize;

use crate::service::{ResolveFailure, SignFailure};

/// The JSON object nested under the public `error` key.
#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ApiErrorBody {
    pub code: String,
    pub message: String,
    pub retryable: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct ApiErrorEnvelope {
    pub error: ApiErrorBody,
}

/// Internal error variants retain enough information to map upstream behavior without putting
/// implementation details into the response body.
#[derive(Debug, Clone, thiserror::Error)]
pub enum ApiError {
    #[error("uniqueId must be a valid TikTok username")]
    InvalidUniqueId,
    #[error("TikTok user could not be resolved")]
    UserNotFound,
    #[error("authentication is required")]
    AuthenticationRequired,
    #[error("API key is invalid")]
    InvalidApiKey,
    #[error("too many connection requests")]
    RateLimited {
        message: String,
        retry_after_ms: u64,
    },
    #[error("discovery failed: {0}")]
    DiscoveryFailed(String),
    #[error("TikTok refused the request: {0}")]
    TikTokRefused(String),
    #[error("signer is unavailable: {0}")]
    SignerUnavailable(String),
    #[error("signing failed: {0}")]
    SignFailed(String),
    #[error("internal server error")]
    Internal(String),
}

impl ApiError {
    pub fn code(&self) -> &'static str {
        match self {
            Self::InvalidUniqueId => "INVALID_UNIQUE_ID",
            Self::UserNotFound => "USER_NOT_FOUND",
            Self::AuthenticationRequired => "AUTHENTICATION_REQUIRED",
            Self::InvalidApiKey => "INVALID_API_KEY",
            Self::RateLimited { .. } => "RATE_LIMITED",
            Self::DiscoveryFailed(_) => "DISCOVERY_FAILED",
            Self::TikTokRefused(_) => "TIKTOK_REFUSED",
            Self::SignerUnavailable(_) => "SIGNER_UNAVAILABLE",
            Self::SignFailed(_) => "SIGN_FAILED",
            Self::Internal(_) => "INTERNAL_ERROR",
        }
    }

    pub fn status(&self) -> StatusCode {
        match self {
            Self::InvalidUniqueId => StatusCode::BAD_REQUEST,
            Self::UserNotFound => StatusCode::NOT_FOUND,
            Self::AuthenticationRequired | Self::InvalidApiKey => StatusCode::UNAUTHORIZED,
            Self::RateLimited { .. } => StatusCode::TOO_MANY_REQUESTS,
            Self::DiscoveryFailed(_) => StatusCode::BAD_GATEWAY,
            Self::TikTokRefused(_) => StatusCode::BAD_GATEWAY,
            Self::SignerUnavailable(_) => StatusCode::SERVICE_UNAVAILABLE,
            Self::SignFailed(_) => StatusCode::BAD_GATEWAY,
            Self::Internal(_) => StatusCode::INTERNAL_SERVER_ERROR,
        }
    }

    pub fn retryable(&self) -> bool {
        match self {
            Self::RateLimited { .. }
            | Self::DiscoveryFailed(_)
            | Self::SignerUnavailable(_)
            | Self::SignFailed(_) => true,
            Self::InvalidUniqueId
            | Self::UserNotFound
            | Self::AuthenticationRequired
            | Self::InvalidApiKey
            | Self::TikTokRefused(_)
            | Self::Internal(_) => false,
        }
    }

    pub fn retry_after_ms(&self) -> Option<u64> {
        match self {
            Self::RateLimited { retry_after_ms, .. } => Some(*retry_after_ms),
            _ => None,
        }
    }

    pub fn body(&self) -> ApiErrorEnvelope {
        ApiErrorEnvelope {
            error: ApiErrorBody {
                code: self.code().into(),
                message: self.message(),
                retryable: self.retryable(),
                retry_after_ms: self.retry_after_ms(),
            },
        }
    }

    fn message(&self) -> String {
        match self {
            Self::RateLimited { message, .. } => message.clone(),
            _ => self.to_string(),
        }
    }
}

impl From<ResolveFailure> for ApiError {
    fn from(error: ResolveFailure) -> Self {
        match error {
            ResolveFailure::Capacity => Self::RateLimited {
                message: "Discovery capacity is currently full".into(),
                retry_after_ms: 500,
            },
            ResolveFailure::Timeout => Self::DiscoveryFailed("TikTok discovery timed out".into()),
            ResolveFailure::NotFound(_) => Self::UserNotFound,
            ResolveFailure::Status {
                status: 429,
                message: _,
            } => Self::RateLimited {
                message: "TikTok discovery is rate limited".into(),
                retry_after_ms: 1_500,
            },
            ResolveFailure::Status { status, message }
                if status == 401 || status == 403 || status == 412 =>
            {
                Self::TikTokRefused(format!("HTTP {status}: {message}"))
            }
            ResolveFailure::Status { status, message } => {
                Self::DiscoveryFailed(format!("HTTP {status}: {message}"))
            }
            ResolveFailure::Transport(message) => Self::DiscoveryFailed(message),
            ResolveFailure::Decode(message) => Self::DiscoveryFailed(message),
            ResolveFailure::Refused(message) => Self::TikTokRefused(message),
        }
    }
}

impl From<SignFailure> for ApiError {
    fn from(error: SignFailure) -> Self {
        match error {
            SignFailure::Refused(message) => Self::TikTokRefused(message),
            SignFailure::Unavailable(message) => Self::SignerUnavailable(message),
            SignFailure::Failed(message) => Self::SignFailed(message),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rate_limit_error_has_the_documented_shape() {
        let error = ApiError::RateLimited {
            message: "Signing capacity is currently full".into(),
            retry_after_ms: 500,
        };
        assert_eq!(error.code(), "RATE_LIMITED");
        assert_eq!(error.status(), StatusCode::TOO_MANY_REQUESTS);
        assert!(error.retryable());
        assert_eq!(error.body().error.retry_after_ms, Some(500));
    }

    #[test]
    fn invalid_input_is_not_retryable() {
        let error = ApiError::InvalidUniqueId;
        assert_eq!(error.code(), "INVALID_UNIQUE_ID");
        assert!(!error.retryable());
    }
}
