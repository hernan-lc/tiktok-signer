use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use tracing::info;
use ttl_live_api::identity::bootstrap_guest_identity;
use ttl_live_api::service::{BackendSigner, DiscoveryResolver};
use ttl_live_api::{router, AppConfig, ConnectService};
use ttl_live_discovery::{DiscoveryClient, UrlSigner};
use ttl_sign_core::{CookieJar, DevicePreset, LocationPreset, Preset, ScreenPreset};
use ttl_sign_embedded::{EmbeddedSigner, Profile};
use ttl_sign_headless::{HeadlessBackend, HeadlessConfig, TRANSPORT_PRODUCT};

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "ttl_live_api=info,ttl_sign_headless=info".into()),
        )
        .init();

    let config = AppConfig::from_env();
    let preset = Preset::new(
        DevicePreset::chrome_linux(),
        LocationPreset::us_east(),
        ScreenPreset::FHD,
    );
    let metrics = Arc::new(ttl_live_api::metrics::Metrics::default());
    let session = match load_session() {
        Some(session) => session,
        None => {
            let identity = bootstrap_guest_identity(
                &preset.user_agent(),
                config.request_timeout,
                Some(&metrics),
            )
            .await
            .context("could not bootstrap anonymous TikTok guest identity")?;
            identity.cookies
        }
    };

    let bundle_path = bundle_path();
    let source = std::fs::read_to_string(&bundle_path)
        .with_context(|| format!("could not read signing bundle at {}", bundle_path.display()))?;
    let profile = Profile {
        user_agent: Some(preset.user_agent()),
        cookie: Some(session.to_cookie_string()),
        ..Profile::default()
    };
    // The signer is started before the listener is bound. Readiness therefore means the engine
    // and bundle were actually initialized, not merely that the process exists.
    let embedded = EmbeddedSigner::with_product(source, profile, TRANSPORT_PRODUCT)
        .context("could not start the warm embedded signer")?;
    let signer: Box<dyn UrlSigner> = Box::new(embedded);
    let backend = HeadlessBackend::new(
        HeadlessConfig::new(preset.clone(), session.clone()).with_timeout(config.request_timeout),
        signer,
    )
    .context("could not build the headless signer backend")?;

    let discovery = DiscoveryClient::with_timeout(&preset, config.request_timeout)
        .context("could not build the TikTok discovery client")?
        .with_session(session.to_cookie_string());
    let resolver = Arc::new(DiscoveryResolver::new(discovery));
    let signer = Arc::new(BackendSigner::new(Arc::new(backend)));
    let service = ConnectService::with_metrics(resolver, signer, config.clone(), metrics);

    let listener = tokio::net::TcpListener::bind(&config.bind)
        .await
        .with_context(|| format!("could not listen on {}", config.bind))?;
    info!(bind = %config.bind, bundle = %bundle_path.display(), "ttl-live-api ready");
    axum::serve(listener, router(service))
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .context("HTTP server stopped unexpectedly")
}

fn bundle_path() -> PathBuf {
    std::env::var_os("SIGNING_BUNDLE")
        .or_else(|| std::env::var_os("TTL_BUNDLE"))
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("webmssdk.js"))
}

fn load_session() -> Option<CookieJar> {
    if let Some(raw) = std::env::var_os("TTL_SESSION_COOKIE") {
        let jar = CookieJar::parse(&raw.to_string_lossy());
        if !jar.is_empty() {
            return Some(jar);
        }
    }
    session_path()
        .and_then(|path| std::fs::read_to_string(path).ok())
        .map(|raw| CookieJar::parse(raw.trim()))
        .filter(|jar| !jar.is_empty())
}

fn session_path() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os("TTL_SESSION_FILE") {
        return Some(PathBuf::from(path));
    }
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("USERPROFILE").map(|path| PathBuf::from(path).join(".config"))
        })?;
    Some(base.join("ttl-signer").join("session"))
}
