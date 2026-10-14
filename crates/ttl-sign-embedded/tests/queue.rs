use std::sync::{Arc, Barrier};
use std::time::Duration;
use ttl_live_discovery::SigningProduct;
use ttl_sign_embedded::{EmbeddedError, Engine, Profile, Signer, SignerConfig};

static GATE: std::sync::OnceLock<Arc<Barrier>> = std::sync::OnceLock::new();
struct Fake;
impl Engine for Fake {
    const NAME: &'static str = "test";
    fn start(bundle: &str, _: &str) -> Result<Self, EmbeddedError> {
        if bundle == "fail" {
            Err(EmbeddedError::Bundle("test failure".into()))
        } else {
            Ok(Self)
        }
    }
    fn sign(&mut self, url: &str, _: &str) -> Result<String, String> {
        match url {
            "blocked" => {
                GATE.get().unwrap().wait();
                GATE.get().unwrap().wait();
            }
            "panic" | "cancelled" | "expired" => panic!("synthetic worker failure"),
            "slow" => std::thread::sleep(Duration::from_millis(100)),
            _ => (),
        }
        Ok(url.into())
    }
}

#[tokio::test]
async fn bounded_submission_cancellation_and_shutdown() {
    let gate = Arc::new(Barrier::new(2));
    GATE.set(gate.clone()).unwrap();
    let signer = Arc::new(
        Signer::<Fake>::with_config(
            "",
            Profile::default(),
            SigningProduct::WsDirect,
            SignerConfig {
                queue_capacity: 1,
                request_timeout: Duration::from_secs(2),
            },
        )
        .unwrap(),
    );
    let first = {
        let signer = signer.clone();
        tokio::spawn(async move { signer.sign_with("blocked", SigningProduct::WsDirect).await })
    };
    tokio::task::spawn_blocking({
        let gate = gate.clone();
        move || {
            gate.wait();
        }
    })
    .await
    .unwrap();
    let cancelled = {
        let signer = signer.clone();
        tokio::spawn(async move {
            signer
                .sign_with("cancelled", SigningProduct::WsDirect)
                .await
        })
    };
    tokio::task::yield_now().await;
    assert_eq!(signer.metrics().queue_depth, 1);
    let error = signer
        .sign_with("overflow", SigningProduct::WsDirect)
        .await
        .unwrap_err();
    assert!(error.to_string().contains("queue is full"));
    assert_eq!(signer.metrics().queue_rejections, 1);
    cancelled.abort();
    assert!(cancelled.await.unwrap_err().is_cancelled());
    tokio::task::spawn_blocking(move || {
        gate.wait();
    })
    .await
    .unwrap();
    assert_eq!(first.await.unwrap().unwrap(), "blocked");
    // Receiver closure is observed before invoking the engine for cancelled work.
    for _ in 0..100 {
        if signer.metrics().queue_depth == 0 {
            break;
        }
        tokio::task::yield_now().await;
    }
    assert_eq!(
        signer
            .sign_with("next", SigningProduct::WsDirect)
            .await
            .unwrap(),
        "next"
    );
    Arc::try_unwrap(signer).ok().unwrap().shutdown().await;
}

#[tokio::test]
async fn worker_crash_and_initialization_failure_are_reported() {
    assert!(Signer::<Fake>::new("fail", Profile::default()).is_err());
    for _ in 0..10 {
        let signer = Signer::<Fake>::new("", Profile::default()).unwrap();
        assert_eq!(
            signer
                .sign_with("ok", SigningProduct::WsDirect)
                .await
                .unwrap(),
            "ok"
        );
        signer.shutdown().await;
    }
    let signer = Signer::<Fake>::new("", Profile::default()).unwrap();
    assert!(signer
        .sign_with("panic", SigningProduct::WsDirect)
        .await
        .is_err());
    assert!(signer
        .sign_with("after", SigningProduct::WsDirect)
        .await
        .is_err());
    signer.shutdown().await;
}

#[test]
fn invalid_capacity_is_an_error_instead_of_a_channel_panic() {
    assert!(Signer::<Fake>::with_config(
        "",
        Profile::default(),
        SigningProduct::WsDirect,
        SignerConfig {
            queue_capacity: 0,
            ..SignerConfig::default()
        }
    )
    .is_err());
}

#[tokio::test]
async fn queued_requests_time_out_and_are_never_executed() {
    let signer = Arc::new(
        Signer::<Fake>::with_config(
            "",
            Profile::default(),
            SigningProduct::WsDirect,
            SignerConfig {
                queue_capacity: 2,
                request_timeout: Duration::from_millis(10),
            },
        )
        .unwrap(),
    );
    let first = {
        let signer = signer.clone();
        tokio::spawn(async move { signer.sign_with("slow", SigningProduct::WsDirect).await })
    };
    tokio::time::sleep(Duration::from_millis(5)).await;
    assert!(signer
        .sign_with("expired", SigningProduct::WsDirect)
        .await
        .unwrap_err()
        .to_string()
        .contains("timed out"));
    assert!(first.await.unwrap().is_err());
    tokio::time::sleep(Duration::from_millis(120)).await;
    assert_eq!(
        signer
            .sign_with("ok", SigningProduct::WsDirect)
            .await
            .unwrap(),
        "ok"
    );
    assert_eq!(signer.metrics().worker_failures, 0);
    Arc::try_unwrap(signer).ok().unwrap().shutdown().await;
}

#[test]
fn overflowing_configuration_returns_an_error() {
    for config in [
        SignerConfig {
            queue_capacity: usize::MAX,
            ..SignerConfig::default()
        },
        SignerConfig {
            request_timeout: Duration::MAX,
            ..SignerConfig::default()
        },
    ] {
        assert!(Signer::<Fake>::with_config(
            "",
            Profile::default(),
            SigningProduct::WsDirect,
            config
        )
        .is_err());
    }
}

#[tokio::test]
async fn metrics_observer_does_not_retain_worker_or_queue() {
    let signer =
        Signer::<Fake>::with_product("", Profile::default(), SigningProduct::WsDirect).unwrap();
    let observer = signer.observer();
    assert!(signer
        .sign_with("panic", SigningProduct::WsDirect)
        .await
        .is_err());
    signer.shutdown().await;
    assert_eq!(observer.metrics().queue_depth, 0);
    assert_eq!(observer.metrics().worker_failures, 1);
}
