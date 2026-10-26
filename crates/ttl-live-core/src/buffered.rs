//! Owned callback dispatcher. Transport never waits for a consumer callback.
use crate::{Error, LiveSink, SocketSigner, State};
use std::sync::{
    atomic::{AtomicBool, AtomicUsize, Ordering},
    mpsc, Arc,
};
use tokio::sync::{oneshot, watch};

const QUEUE_CAPACITY: usize = 32;
const QUEUE_ESTIMATED_BYTES: usize = 8 * 1024 * 1024;
enum Notice {
    State(State, String),
    Error(Error, bool),
    Batch(ttl_live_events::EventBatch),
    Decode(std::time::Duration),
    Ack(std::time::Duration),
}
struct Proxy {
    sender: mpsc::SyncSender<(Notice, usize)>,
    bytes: Arc<AtomicUsize>,
    overloaded: Arc<AtomicBool>,
    stop: watch::Sender<bool>,
}
impl Proxy {
    #[allow(deprecated)] // fetch_update supports the workspace MSRV (1.88).
    fn send(&self, notice: Notice, cost: usize) {
        if self.overloaded.load(Ordering::Acquire) {
            return;
        }
        let reserved = self
            .bytes
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |bytes| {
                bytes
                    .checked_add(cost)
                    .filter(|sum| *sum <= QUEUE_ESTIMATED_BYTES)
            })
            .is_ok();
        if !reserved || self.sender.try_send((notice, cost)).is_err() {
            if reserved {
                self.bytes.fetch_sub(cost, Ordering::AcqRel);
            }
            self.overloaded.store(true, Ordering::Release);
            self.stop.send_replace(true);
        }
    }
}
impl LiveSink for Proxy {
    fn on_state(&self, state: State, detail: &str) {
        self.send(Notice::State(state, detail.into()), 512);
    }
    fn on_error(&self, error: Error, retryable: bool) {
        self.send(Notice::Error(error, retryable), 128);
    }
    fn on_batch(&self, batch: ttl_live_events::EventBatch) {
        let cost = batch.estimated_retained_bytes();
        self.send(Notice::Batch(batch), cost);
    }
    fn on_decode_duration(&self, duration: std::time::Duration) {
        self.send(Notice::Decode(duration), 128);
    }
    fn on_ack_duration(&self, duration: std::time::Duration) {
        self.send(Notice::Ack(duration), 128);
    }
}
/// Runs callbacks in order on a dedicated thread, including on a current-thread runtime.
/// Queue saturation stops the transport and reports Backpressure after draining accepted notices.
/// Returns only after the dispatcher exits. A callback must eventually return for shutdown to join.
/// Metrics share the bounded queue; they cannot invoke user code on the transport thread.
pub async fn run_buffered(
    signer: &impl SocketSigner,
    cookies: &ttl_sign_core::CookieJar,
    preset: &ttl_sign_core::Preset,
    room: &str,
    max_retries: u32,
    sink: impl LiveSink + Send + 'static,
    mut stop: watch::Receiver<bool>,
) -> Result<(), std::io::Error> {
    let (cancel, rx) = watch::channel(*stop.borrow());
    let (sender, incoming) = mpsc::sync_channel::<(Notice, usize)>(QUEUE_CAPACITY);
    let bytes = Arc::new(AtomicUsize::new(0));
    let overloaded = Arc::new(AtomicBool::new(false));
    let proxy = Proxy {
        sender,
        bytes: bytes.clone(),
        overloaded: overloaded.clone(),
        stop: cancel.clone(),
    };
    let (finished, mut done) = oneshot::channel();
    let worker = std::thread::Builder::new()
        .name("ttl-live-callbacks".into())
        .spawn(move || {
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                while let Ok((notice, cost)) = incoming.recv() {
                    // Release after invocation, so an active callback also consumes the byte budget.
                    match notice {
                        Notice::State(state, detail) => sink.on_state(state, &detail),
                        Notice::Error(error, retryable) => sink.on_error(error, retryable),
                        Notice::Batch(batch) => sink.on_batch(batch),
                        Notice::Decode(duration) => sink.on_decode_duration(duration),
                        Notice::Ack(duration) => sink.on_ack_duration(duration),
                    }
                    bytes.fetch_sub(cost, Ordering::AcqRel);
                }
                if overloaded.load(Ordering::Acquire) {
                    sink.on_error(Error::Backpressure, false);
                    sink.on_state(State::Error, "LIVE callback queue saturated");
                    sink.on_state(State::Disconnected, "stream stopped");
                }
            }));
            let _ = finished.send(outcome.is_ok());
        })?;
    let dispatcher_result = {
        let transport = crate::run(signer, cookies, preset, room, max_retries, &proxy, rx);
        tokio::pin!(transport);
        tokio::select! {
            _ = &mut transport => None,
            _ = crate::cancelled(&mut stop) => { cancel.send_replace(true); transport.await; None },
            result = &mut done => { cancel.send_replace(true); transport.await; Some(result) },
        }
    };
    // The future holds a borrow only; drop all producers before awaiting the receiver's drain.
    drop(proxy);
    let result = match dispatcher_result {
        Some(result) => result,
        None => done.await,
    };
    let _ = worker.join();
    if matches!(result, Ok(true)) {
        Ok(())
    } else {
        Err(std::io::Error::other("LIVE callback dispatcher panicked"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn metadata_alone_cannot_bypass_the_queue_byte_budget() {
        let (sender, incoming) = mpsc::sync_channel(QUEUE_CAPACITY);
        let (stop, rx) = watch::channel(false);
        let proxy = Proxy {
            sender,
            bytes: Arc::new(AtomicUsize::new(0)),
            overloaded: Arc::new(AtomicBool::new(false)),
            stop,
        };
        let batch = ttl_live_events::EventBatch {
            cursor: "x".repeat(QUEUE_ESTIMATED_BYTES),
            ..Default::default()
        };
        proxy.on_batch(batch);
        assert!(*rx.borrow());
        assert!(proxy.overloaded.load(Ordering::Acquire));
        assert_eq!(proxy.bytes.load(Ordering::Acquire), 0);
        assert!(incoming.try_recv().is_err());
    }
}
