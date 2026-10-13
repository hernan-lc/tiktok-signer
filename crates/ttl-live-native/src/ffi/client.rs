use super::*;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::{
    sync::{Arc, Condvar, Mutex, MutexGuard},
    thread::{self, JoinHandle, ThreadId},
};
use tokio::sync::watch;
use ttl_live_core::{Error, LiveSink, State};
use ttl_live_discovery::{DiscoveryClient, DiscoveryError, SigningProduct};
use ttl_sign_core::{CookieJar, DevicePreset, LocationPreset, Preset, ScreenPreset};
use ttl_sign_embedded::{EmbeddedSigner, Profile};

pub type EventCallback = unsafe extern "C" fn(*mut std::ffi::c_void, *const NativeEvent);
pub type BatchCallback = unsafe extern "C" fn(*mut std::ffi::c_void, *const NativeBatch);
pub type StateCallback = unsafe extern "C" fn(*mut std::ffi::c_void, i32, *const c_char, usize);
pub type ErrorCallback = unsafe extern "C" fn(*mut std::ffi::c_void, *const ErrorInfo);
#[repr(C)]
pub struct ErrorInfo {
    pub code: ResultCode,
    pub message: *const c_char,
    pub message_len: usize,
    pub retryable: bool,
    pub retry_after_ms: i64,
    pub http_status: i32,
}
#[repr(C)]
pub struct LiveOptions {
    pub struct_size: u32,
    pub unique_id: *const c_char,
    pub unique_id_len: usize,
    pub bundle: *const c_char,
    pub bundle_len: usize,
    pub reconnect: bool,
    pub max_reconnect_attempts: u32,
    /// Optional extension. No cookie is logged or included in event JSON.
    pub cookie_header: *const c_char,
    pub cookie_header_len: usize,
}
#[derive(Default)]
struct Callbacks {
    event: Option<(EventCallback, usize)>,
    batch: Option<(BatchCallback, usize)>,
    state: Option<(StateCallback, usize)>,
    error: Option<(ErrorCallback, usize)>,
    enabled: bool,
    active: usize,
    worker: Option<ThreadId>,
    dispatcher: Option<ThreadId>,
}
enum Delivery {
    Batch(ttl_live_events::EventBatch),
    State(State, String),
    Error(ResultCode, bool, i32, String),
}
impl Delivery {
    fn event_count(&self) -> u64 {
        match self {
            Self::Batch(b) => b.events.len() as u64,
            _ => 0,
        }
    }
}
#[derive(Default)]
struct Dispatch {
    capacity: usize,
    sender: Option<tokio::sync::mpsc::Sender<Delivery>>,
}
struct Shared {
    callbacks: Mutex<Callbacks>,
    idle: Condvar,
    dispatch: Mutex<Dispatch>,
    stop: Mutex<Option<watch::Sender<bool>>>,
    overloaded: AtomicBool,
    received: AtomicU64,
    dispatched: AtomicU64,
    discarded: AtomicU64,
    rejections: AtomicU64,
}
fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}
struct Invocation<'a>(&'a Shared);
impl Drop for Invocation<'_> {
    fn drop(&mut self) {
        let mut c = lock(&self.0.callbacks);
        c.active -= 1;
        self.0.idle.notify_all();
    }
}
impl Shared {
    fn invoke<T: Copy>(
        &self,
        select: impl FnOnce(&Callbacks) -> Option<T>,
        f: impl FnOnce(T),
    ) -> bool {
        let selected = {
            let mut c = lock(&self.callbacks);
            if !c.enabled {
                return false;
            }
            let Some(cb) = select(&c) else {
                return false;
            };
            c.active += 1;
            cb
        };
        let _invocation = Invocation(self);
        f(selected);
        true
    }
    fn on_worker(&self) -> bool {
        {
            let c = lock(&self.callbacks);
            let id = Some(thread::current().id());
            c.worker == id || c.dispatcher == id
        }
    }
    fn update(&self, update: impl FnOnce(&mut Callbacks)) {
        let mut c = lock(&self.callbacks);
        update(&mut c);
        if c.worker == Some(thread::current().id()) || c.dispatcher == Some(thread::current().id())
        {
            return;
        }
        while c.active != 0 {
            c = self.idle.wait(c).unwrap_or_else(|e| e.into_inner());
        }
    }
    fn disable(&self) {
        self.update(|c| c.enabled = false);
    }
    fn enqueue(&self, delivery: Delivery) -> Option<Delivery> {
        let mut dispatch = lock(&self.dispatch);
        if dispatch.capacity == 0 {
            return Some(delivery);
        }
        let Some(sender) = &dispatch.sender else {
            self.discarded
                .fetch_add(delivery.event_count(), Ordering::Relaxed);
            return None;
        };
        match sender.try_send(delivery) {
            Ok(()) => (),
            Err(error) => {
                self.discarded
                    .fetch_add(error.into_inner().event_count(), Ordering::Relaxed);
                self.rejections.fetch_add(1, Ordering::Relaxed);
                self.overloaded.store(true, Ordering::Release);
                dispatch.sender.take();
                if let Some(stop) = lock(&self.stop).as_ref() {
                    stop.send_replace(true);
                }
            }
        }
        None
    }
    fn error(&self, code: ResultCode, retryable: bool, status: i32, message: &str) {
        if let Some(Delivery::Error(code, retryable, status, message)) =
            self.enqueue(Delivery::Error(code, retryable, status, message.into()))
        {
            self.deliver_error(code, retryable, status, &message);
        }
    }
    fn deliver_error(&self, code: ResultCode, retryable: bool, status: i32, message: &str) {
        let s = cstring(message);
        let e = ErrorInfo {
            code,
            message: s.as_ptr(),
            message_len: s.as_bytes().len(),
            retryable,
            retry_after_ms: -1,
            http_status: status,
        };
        self.invoke(
            |c| c.error,
            |(cb, data)| unsafe {
                cb(data as *mut _, &e);
            },
        );
    }
}
impl Shared {
    fn deliver_state(&self, state: State, detail: &str) {
        let s = cstring(detail);
        self.invoke(
            |c| c.state,
            |(cb, data)| unsafe {
                cb(data as *mut _, state as i32, s.as_ptr(), s.as_bytes().len());
            },
        );
    }
    fn report_error(&self, e: Error, retryable: bool) {
        let (code, msg) = match e {
            Error::Auth(_) => (ResultCode::Auth, "LIVE handshake refused"),
            Error::SocketStatus(_) => (ResultCode::Socket, "LIVE handshake failed"),
            Error::Signer => (ResultCode::Signer, "signing failed"),
            Error::Socket => (ResultCode::Socket, "LIVE socket failed"),
            Error::Timeout => (ResultCode::Timeout, "LIVE socket timed out"),
            Error::Decode => (ResultCode::Decode, "invalid LIVE batch"),
            Error::Protocol => (ResultCode::Protocol, "LIVE protocol failed"),
        };
        let status = match e {
            Error::Auth(status) | Error::SocketStatus(status) => status as i32,
            _ => 0,
        };
        self.error(code, retryable, status, msg);
    }
    fn deliver_batch(&self, b: ttl_live_events::EventBatch) {
        let batch = NativeBatch::new(b);
        let batch_invoked = self.invoke(
            |c| c.batch,
            |(cb, data)| unsafe {
                cb(data as *mut _, &batch);
            },
        );
        for (index, event) in batch.events.iter().enumerate() {
            if self.overloaded.load(Ordering::Acquire) {
                let remaining = (batch.events.len() - index) as u64;
                if batch_invoked {
                    self.dispatched.fetch_add(remaining, Ordering::Relaxed);
                } else {
                    self.discarded.fetch_add(remaining, Ordering::Relaxed);
                }
                break;
            }
            let invoked = self.invoke(
                |c| c.event,
                |(cb, data)| unsafe {
                    cb(data as *mut _, event);
                },
            );
            if invoked || batch_invoked {
                self.dispatched.fetch_add(1, Ordering::Relaxed);
            } else {
                self.discarded.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
}
impl LiveSink for Shared {
    fn on_state(&self, state: State, detail: &str) {
        if let Some(Delivery::State(state, detail)) =
            self.enqueue(Delivery::State(state, detail.into()))
        {
            self.deliver_state(state, &detail);
        }
    }
    fn on_error(&self, error: Error, retryable: bool) {
        self.report_error(error, retryable);
    }
    fn on_batch(&self, batch: ttl_live_events::EventBatch) {
        self.received
            .fetch_add(batch.events.len() as u64, Ordering::Relaxed);
        if let Some(Delivery::Batch(batch)) = self.enqueue(Delivery::Batch(batch)) {
            self.deliver_batch(batch);
        }
    }
}
fn dispatcher(shared: Arc<Shared>, mut incoming: tokio::sync::mpsc::Receiver<Delivery>) {
    lock(&shared.callbacks).dispatcher = Some(thread::current().id());
    while let Some(delivery) = incoming.blocking_recv() {
        if shared.overloaded.load(Ordering::Acquire) || !lock(&shared.callbacks).enabled {
            shared
                .discarded
                .fetch_add(delivery.event_count(), Ordering::Relaxed);
            while let Ok(delivery) = incoming.try_recv() {
                shared
                    .discarded
                    .fetch_add(delivery.event_count(), Ordering::Relaxed);
            }
            break;
        }
        match delivery {
            Delivery::Batch(batch) => shared.deliver_batch(batch),
            Delivery::State(state, detail) => shared.deliver_state(state, &detail),
            Delivery::Error(code, retryable, status, message) => {
                shared.deliver_error(code, retryable, status, &message)
            }
        }
    }
    if shared.overloaded.swap(false, Ordering::AcqRel) {
        shared.deliver_error(
            ResultCode::InvalidState,
            false,
            0,
            "event delivery queue overflow; LIVE connection stopped",
        );
        shared.disable();
    }
    lock(&shared.callbacks).dispatcher = None;
}
struct Signer(Arc<EmbeddedSigner>);
impl ttl_live_core::SocketSigner for Signer {
    fn sign<'a>(&'a self, url: &'a str) -> ttl_live_core::SignFuture<'a> {
        Box::pin(async move {
            self.0
                .sign_with(url, SigningProduct::WsDirect)
                .await
                .map_err(|_| ())
        })
    }
}
struct Control {
    worker: Option<JoinHandle<()>>,
    dispatcher: Option<JoinHandle<()>>,
    stop: Option<watch::Sender<bool>>,
    stopping: bool,
}
struct Inputs {
    signer: Option<Arc<EmbeddedSigner>>,
    cookies: CookieJar,
    bundle: String,
}
pub struct NativeClient {
    shared: Arc<Shared>,
    control: Mutex<Control>,
    inputs: Arc<Mutex<Inputs>>,
    unique_id: String,
    preset: Preset,
    max_retries: u32,
}
impl NativeClient {
    fn disconnect(&self) -> ResultCode {
        // Disable delivery before signaling stop. Clearing or disconnecting on the
        // callback thread is reentrant and never attempts to join itself.
        self.shared.disable();
        lock(&self.shared.dispatch).sender.take();
        let (worker, dispatcher) = {
            let mut c = lock(&self.control);
            if let Some(stop) = &c.stop {
                stop.send_replace(true);
            }
            if self.shared.on_worker() {
                return ResultCode::Ok;
            }
            if c.stopping {
                fail(ResultCode::InvalidState, "disconnect already in progress");
                return ResultCode::InvalidState;
            }
            c.stopping = true;
            (c.worker.take(), c.dispatcher.take())
        };
        if let Some(w) = worker {
            let _ = w.join();
        }
        if let Some(w) = dispatcher {
            let _ = w.join();
        }
        lock(&self.shared.stop).take();
        let mut c = lock(&self.control);
        c.stop = None;
        c.stopping = false;
        ResultCode::Ok
    }
}
#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_create(options: *const LiveOptions) -> *mut NativeClient {
    super::guard(ptr::null_mut(), || {
        if options.is_null() {
            fail(ResultCode::InvalidArgument, "NULL options");
            return ptr::null_mut();
        }
        let size = ptr::addr_of!((*options).struct_size).read() as usize;
        if size < std::mem::offset_of!(LiveOptions, cookie_header) {
            fail(
                ResultCode::InvalidArgument,
                "options struct_size is too small",
            );
            return ptr::null_mut();
        }
        let Some(id) = text(
            ptr::addr_of!((*options).unique_id).read(),
            ptr::addr_of!((*options).unique_id_len).read(),
            4096,
        ) else {
            return ptr::null_mut();
        };
        let Some(bundle) = text(
            ptr::addr_of!((*options).bundle).read(),
            ptr::addr_of!((*options).bundle_len).read(),
            8 * 1024 * 1024,
        ) else {
            return ptr::null_mut();
        };
        if id.trim_start_matches('@').is_empty() || id.contains('\0') || bundle.is_empty() {
            fail(
                ResultCode::InvalidArgument,
                "unique_id and explicit bundle are required",
            );
            return ptr::null_mut();
        }
        // Treat the ABI boolean as a byte so invalid C input cannot construct an invalid Rust bool.
        let reconnect = ptr::addr_of!((*options).reconnect).cast::<u8>().read();
        if reconnect > 1 {
            fail(ResultCode::InvalidArgument, "invalid reconnect boolean");
            return ptr::null_mut();
        }
        let retries = ptr::addr_of!((*options).max_reconnect_attempts).read();
        if retries > 1000 {
            fail(ResultCode::InvalidArgument, "reconnect budget exceeds 1000");
            return ptr::null_mut();
        }
        let cookie = if size >= std::mem::size_of::<LiveOptions>() {
            let Some(c) = text(
                ptr::addr_of!((*options).cookie_header).read(),
                ptr::addr_of!((*options).cookie_header_len).read(),
                64 * 1024,
            ) else {
                return ptr::null_mut();
            };
            c
        } else {
            ""
        };
        let preset = Preset::new(
            DevicePreset::chrome_linux(),
            LocationPreset::us_east(),
            ScreenPreset::FHD,
        );
        let cookies = CookieJar::parse(cookie);
        let signer = if cookies.is_empty() {
            None
        } else {
            match EmbeddedSigner::with_product(
                bundle,
                Profile {
                    user_agent: Some(preset.user_agent()),
                    cookie: Some(cookie.to_owned()),
                    ..Profile::default()
                },
                SigningProduct::WsDirect,
            ) {
                Ok(s) => Some(Arc::new(s)),
                Err(_) => {
                    fail(
                        ResultCode::Signer,
                        "could not prepare the explicit signing bundle",
                    );
                    return ptr::null_mut();
                }
            }
        };
        Box::into_raw(Box::new(NativeClient {
            shared: Arc::new(Shared {
                callbacks: Mutex::new(Callbacks::default()),
                idle: Condvar::new(),
                dispatch: Mutex::new(Dispatch::default()),
                stop: Mutex::new(None),
                overloaded: AtomicBool::new(false),
                received: AtomicU64::new(0),
                dispatched: AtomicU64::new(0),
                discarded: AtomicU64::new(0),
                rejections: AtomicU64::new(0),
            }),
            control: Mutex::new(Control {
                worker: None,
                dispatcher: None,
                stop: None,
                stopping: false,
            }),
            inputs: Arc::new(Mutex::new(Inputs {
                signer,
                cookies,
                bundle: bundle.to_owned(),
            })),
            unique_id: id.to_owned(),
            preset,
            max_retries: if reconnect == 1 { retries } else { 0 },
        }))
    })
}

#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_connect(client: *mut NativeClient) -> ResultCode {
    super::guard(ResultCode::Internal, || {
        let Some(client) = client.as_ref() else {
            fail(ResultCode::InvalidArgument, "NULL client");
            return ResultCode::InvalidArgument;
        };
        let mut c = lock(&client.control);
        if c.stopping
            || c.worker.as_ref().is_some_and(|w| !w.is_finished())
            || c.dispatcher.as_ref().is_some_and(|w| !w.is_finished())
        {
            fail(ResultCode::InvalidState, "client is active or stopping");
            return ResultCode::InvalidState;
        }
        if let Some(w) = c.worker.take() {
            let _ = w.join();
        }
        if let Some(w) = c.dispatcher.take() {
            let _ = w.join();
        }
        let (stop, mut rx) = watch::channel(false);
        let shared = client.shared.clone();
        *lock(&shared.stop) = Some(stop.clone());
        let inputs = client.inputs.clone();
        let id = client.unique_id.clone();
        let preset = client.preset.clone();
        let retries = client.max_retries;
        lock(&shared.callbacks).enabled = true;
        let capacity = lock(&shared.dispatch).capacity;
        if capacity > 0 {
            let (sender, incoming) = tokio::sync::mpsc::channel(capacity);
            lock(&shared.dispatch).sender = Some(sender);
            let dispatch_shared = shared.clone();
            match thread::Builder::new()
                .name("ttl-live-dispatch".into())
                .spawn(move || dispatcher(dispatch_shared, incoming))
            {
                Ok(worker) => c.dispatcher = Some(worker),
                Err(_) => {
                    lock(&shared.dispatch).sender.take();
                    lock(&shared.callbacks).enabled = false;
                    fail(ResultCode::Internal, "could not start callback dispatcher");
                    return ResultCode::Internal;
                }
            }
        }
        let worker = thread::Builder::new().name("ttl-live-native".into()).spawn(move || {
        lock(&shared.callbacks).worker = Some(thread::current().id());
        let result = catch_unwind(AssertUnwindSafe(|| {
            let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                Ok(rt) => rt, Err(_) => { shared.error(ResultCode::Internal,false,0,"could not start LIVE runtime"); return; }
            };
            rt.block_on(async {
                shared.on_state(State::Connecting,"resolving LIVE room");
                let discovery = match DiscoveryClient::new(&preset) { Ok(d) => d,Err(_) => { shared.error(ResultCode::Discovery,false,0,"could not start room discovery"); shared.on_state(State::Error,"discovery failed"); return; } };
                let lookup = tokio::select! { biased;
                    _ = ttl_live_core::cancelled(&mut rx) => return,
                    result = discovery.room_lookup(&id) => result,
                };
                let room = match lookup {
                    Ok(room) if room.is_live() => room.room_id,
                    Ok(_) | Err(DiscoveryError::NoRoom(_)) => { shared.error(ResultCode::Offline,false,0,"creator is offline"); shared.on_state(State::Offline,"creator is offline"); return; },
                    Err(e) => {
                        let status = match e { DiscoveryError::Status { status } => status as i32,_ => 0 };
                        shared.error(if status == 401 || status == 403 { ResultCode::Auth } else { ResultCode::Discovery },false,status,"room discovery failed");
                        shared.on_state(State::Error,"discovery failed"); return;
                    }
                };
                let needs_identity = lock(&inputs).cookies.is_empty();
                if needs_identity {
                    let user_agent = preset.user_agent();
                    let identity = tokio::select! { biased;
                        _ = ttl_live_core::cancelled(&mut rx) => return,
                        result = ttl_live_discovery::identity::bootstrap_guest_identity(&user_agent,std::time::Duration::from_secs(15)) => result,
                    };
                    let identity = match identity {
                        Ok(i) => i,
                        Err(e) => {
                            use ttl_live_discovery::identity::IdentityError;
                            let (code,status,message) = match e {
                                IdentityError::NoCookies => (ResultCode::Auth,0,"guest identity unavailable; explicit session may be required"),
                                IdentityError::Refused(status) => (if status == 401 || status == 403 {ResultCode::Auth} else {ResultCode::Discovery},status as i32,"guest bootstrap refused"),
                                IdentityError::Transport(_) => (ResultCode::Discovery,0,"guest bootstrap transport failed"),
                            };
                            shared.error(code,false,status,message);shared.on_state(State::Error,"identity acquisition failed");return;
                        }
                    };
                    lock(&inputs).cookies = identity.cookies;
                }
                if lock(&inputs).signer.is_none() {
                    let (bundle,cookie) = {let i = lock(&inputs);(i.bundle.clone(),i.cookies.to_cookie_string())};
                    // Engine startup can block; wait on a separate preparation thread
                    // so cancellation always releases the LIVE worker immediately.
                    let (prepared,answer) = tokio::sync::oneshot::channel();
                    let profile = Profile {user_agent:Some(preset.user_agent()),cookie:Some(cookie),..Profile::default()};
                    if thread::Builder::new().name("ttl-live-prepare".into()).spawn(move || {
                        let _ = prepared.send(EmbeddedSigner::with_product(bundle,profile,SigningProduct::WsDirect));
                    }).is_err() { shared.error(ResultCode::Internal,false,0,"could not start signer preparation");return; }
                    let prepared = tokio::select! { biased;
                        _ = ttl_live_core::cancelled(&mut rx) => return,
                        result = answer => result,
                    };
                    match prepared {
                        Ok(Ok(s)) => lock(&inputs).signer = Some(Arc::new(s)),
                        _ => { shared.error(ResultCode::Signer,false,0,"could not prepare the explicit signing bundle");shared.on_state(State::Error,"signer initialization failed");return; }
                    }
                }
                let (signer,cookies) = {let i = lock(&inputs);(Signer(i.signer.as_ref().expect("initialized signer").clone()),i.cookies.clone())};
                ttl_live_core::run(&signer,&cookies,&preset,&room,retries,shared.as_ref(),rx).await;
            });
        }));
        if result.is_err() { shared.error(ResultCode::Internal,false,0,"LIVE worker panicked"); shared.on_state(State::Error,"LIVE worker failed"); }
        lock(&shared.callbacks).worker = None;
    });
        match worker {
            Ok(w) => {
                c.worker = Some(w);
                c.stop = Some(stop);
                ResultCode::Ok
            }
            Err(_) => {
                lock(&client.shared.callbacks).enabled = false;
                lock(&client.shared.dispatch).sender.take();
                if let Some(worker) = c.dispatcher.take() {
                    let _ = worker.join();
                }
                fail(ResultCode::Internal, "could not start LIVE worker");
                ResultCode::Internal
            }
        }
    })
}

#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_disconnect(client: *mut NativeClient) -> ResultCode {
    super::guard(ResultCode::Internal, || {
        client.as_ref().map_or_else(
            || {
                fail(ResultCode::InvalidArgument, "NULL client");
                ResultCode::InvalidArgument
            },
            NativeClient::disconnect,
        )
    })
}

#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_destroy(client: *mut NativeClient) {
    super::guard((), || {
        let Some(c) = client.as_ref() else {
            return;
        };
        if c.shared.on_worker() {
            fail(
                ResultCode::InvalidState,
                "destroy must run outside callbacks; disconnect is reentrant",
            );
            return;
        }
        if c.disconnect() == ResultCode::Ok {
            drop(Box::from_raw(client));
        }
    })
}

/// Configure only while disconnected; zero retains the legacy callback thread.
#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_set_async_delivery(
    client: *mut NativeClient,
    capacity: usize,
) -> ResultCode {
    super::guard(ResultCode::Internal, || {
        let Some(client) = client.as_ref() else {
            fail(ResultCode::InvalidArgument, "NULL client");
            return ResultCode::InvalidArgument;
        };
        if capacity > 256 {
            fail(
                ResultCode::InvalidArgument,
                "delivery capacity exceeds 256 batches",
            );
            return ResultCode::InvalidArgument;
        }
        let control = lock(&client.control);
        if control.stopping
            || control.worker.as_ref().is_some_and(|w| !w.is_finished())
            || control
                .dispatcher
                .as_ref()
                .is_some_and(|w| !w.is_finished())
        {
            fail(
                ResultCode::InvalidState,
                "configure delivery while disconnected",
            );
            return ResultCode::InvalidState;
        }
        lock(&client.shared.dispatch).capacity = capacity;
        ResultCode::Ok
    })
}
#[repr(C)]
pub struct DeliveryMetrics {
    pub queue_depth: usize,
    pub events_received: u64,
    pub events_dispatched: u64,
    pub events_discarded: u64,
    pub queue_rejections: u64,
}
#[no_mangle]
pub unsafe extern "C" fn ttl_live_client_delivery_metrics(
    client: *const NativeClient,
    out: *mut DeliveryMetrics,
) -> bool {
    super::guard(false, || {
        let (Some(client), Some(out)) = (client.as_ref(), out.as_mut()) else {
            return false;
        };
        let dispatch = lock(&client.shared.dispatch);
        *out = DeliveryMetrics {
            queue_depth: dispatch
                .sender
                .as_ref()
                .map_or(0, |sender| sender.max_capacity() - sender.capacity()),
            events_received: client.shared.received.load(Ordering::Relaxed),
            events_dispatched: client.shared.dispatched.load(Ordering::Relaxed),
            events_discarded: client.shared.discarded.load(Ordering::Relaxed),
            queue_rejections: client.shared.rejections.load(Ordering::Relaxed),
        };
        true
    })
}

macro_rules! callback {
    ($name:ident,$field:ident,$ty:ty) => {
        #[no_mangle]
        pub unsafe extern "C" fn $name(
            client: *mut NativeClient,
            callback: Option<$ty>,
            userdata: *mut std::ffi::c_void,
        ) -> ResultCode {
            super::guard(ResultCode::Internal, || {
                let Some(c) = client.as_ref() else {
                    fail(ResultCode::InvalidArgument, "NULL client");
                    return ResultCode::InvalidArgument;
                };
                c.shared
                    .update(|c| c.$field = callback.map(|cb| (cb, userdata as usize)));
                ResultCode::Ok
            })
        }
    };
}
callback!(ttl_live_client_set_event_callback, event, EventCallback);
callback!(ttl_live_client_set_batch_callback, batch, BatchCallback);
callback!(ttl_live_client_set_state_callback, state, StateCallback);
callback!(ttl_live_client_set_error_callback, error, ErrorCallback);

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        sync::{
            atomic::{AtomicUsize, Ordering},
            mpsc, Barrier,
        },
        time::{Duration, Instant},
    };
    const MOCK_BUNDLE: &str = "window.byted_acrawler={init:function(){},registerWsSigner:function(){return function(){return {'X-Gnarly':'test-only'}}}};";
    fn options() -> LiveOptions {
        LiveOptions {
            struct_size: std::mem::size_of::<LiveOptions>() as u32,
            unique_id: c"test-only".as_ptr(),
            unique_id_len: 9,
            bundle: MOCK_BUNDLE.as_ptr().cast(),
            bundle_len: MOCK_BUNDLE.len(),
            reconnect: true,
            max_reconnect_attempts: 5,
            cookie_header: c"ttwid=test".as_ptr(),
            cookie_header_len: 10,
        }
    }
    fn replay() -> ttl_live_events::EventBatch {
        ttl_live_events::EventBatch {
            events: vec![
                ttl_live_events::decode_event_envelope("WebcastFutureMessage", 1, false, &[8, 42]),
                ttl_live_events::decode_event_envelope(
                    "WebcastChatMessage",
                    2,
                    false,
                    &[26, 2, b'h', b'i'],
                ),
            ],
            ..Default::default()
        }
    }
    /// Simulates worker delivery without discovery/credentials, exercising the
    /// production gate, callbacks, disconnect, clone, serialization and destroy.
    unsafe fn start_replay(client: *mut NativeClient) {
        start_replay_rate(client, 2000);
    }
    unsafe fn start_replay_rate(client: *mut NativeClient, rate: u64) {
        let client = &*client;
        let shared = client.shared.clone();
        let (stop, rx) = watch::channel(false);
        *lock(&shared.stop) = Some(stop.clone());
        let capacity = lock(&shared.dispatch).capacity;
        if capacity > 0 {
            let (sender, incoming) = tokio::sync::mpsc::channel(capacity);
            lock(&shared.dispatch).sender = Some(sender);
            let dispatch_shared = shared.clone();
            lock(&client.control).dispatcher =
                Some(thread::spawn(move || dispatcher(dispatch_shared, incoming)));
        }
        let ready = Arc::new(Barrier::new(2));
        let r = ready.clone();
        lock(&shared.callbacks).enabled = true;
        let worker = thread::spawn(move || {
            lock(&shared.callbacks).worker = Some(thread::current().id());
            r.wait();
            while !*rx.borrow() {
                shared.on_batch(replay());
                thread::park_timeout(Duration::from_micros((2_000_000 / rate).max(1)));
            }
            lock(&shared.callbacks).worker = None;
        });
        let mut c = lock(&client.control);
        c.stop = Some(stop);
        c.worker = Some(worker);
        drop(c);
        ready.wait();
    }
    unsafe extern "C" fn count_event(data: *mut std::ffi::c_void, event: *const NativeEvent) {
        let count = &*(data as *const AtomicUsize);
        count.fetch_add(1, Ordering::SeqCst);
        let clone = ttl_event_clone(event);
        let json = ttl_event_to_json(clone, 2);
        assert!(!json.is_null());
        ttl_string_free(json);
        ttl_event_free(clone);
    }
    #[test]
    fn repeated_shutdown_multiple_clients_and_clones_are_quiescent() {
        unsafe {
            let a = ttl_live_client_create(&options());
            let b = ttl_live_client_create(&options());
            assert!(!a.is_null() && !b.is_null());
            let count_a = AtomicUsize::new(0);
            let count_b = AtomicUsize::new(0);
            ttl_live_client_set_event_callback(
                a,
                Some(count_event),
                (&count_a as *const AtomicUsize).cast_mut().cast(),
            );
            ttl_live_client_set_event_callback(
                b,
                Some(count_event),
                (&count_b as *const AtomicUsize).cast_mut().cast(),
            );
            for _ in 0..10 {
                start_replay(a);
                assert_eq!(ttl_live_client_disconnect(a), ResultCode::Ok);
                assert_eq!(ttl_live_client_disconnect(a), ResultCode::Ok);
            }
            start_replay(b);
            ttl_live_client_destroy(a);
            let before = count_a.load(Ordering::SeqCst);
            assert_eq!(ttl_live_client_disconnect(b), ResultCode::Ok);
            ttl_live_client_destroy(b);
            assert_eq!(count_a.load(Ordering::SeqCst), before);
        }
    }
    struct Blocking {
        entered: mpsc::Sender<()>,
        resume: Mutex<mpsc::Receiver<()>>,
    }
    unsafe extern "C" fn block_event(data: *mut std::ffi::c_void, _: *const NativeEvent) {
        let b = &*(data as *const Blocking);
        b.entered.send(()).unwrap();
        lock(&b.resume).recv().unwrap();
    }
    unsafe extern "C" fn count_overflow(data: *mut std::ffi::c_void, error: *const ErrorInfo) {
        assert_eq!((*error).code, ResultCode::InvalidState);
        let message = std::ffi::CStr::from_ptr((*error).message).to_str().unwrap();
        assert!(message.contains("queue overflow"));
        (&*(data as *const AtomicUsize)).fetch_add(1, Ordering::SeqCst);
    }
    #[test]
    fn async_slow_callback_has_bounded_queue_and_explicit_terminal_overflow() {
        unsafe {
            let client = ttl_live_client_create(&options());
            assert_eq!(
                ttl_live_client_set_async_delivery(client, 1),
                ResultCode::Ok
            );
            let (entered, rx) = mpsc::channel();
            let (resume, r) = mpsc::channel();
            let blocking = Blocking {
                entered,
                resume: Mutex::new(r),
            };
            let errors = AtomicUsize::new(0);
            ttl_live_client_set_event_callback(
                client,
                Some(block_event),
                (&blocking as *const Blocking).cast_mut().cast(),
            );
            ttl_live_client_set_error_callback(
                client,
                Some(count_overflow),
                (&errors as *const AtomicUsize).cast_mut().cast(),
            );
            start_replay(client);
            rx.recv_timeout(Duration::from_secs(2)).unwrap();
            let deadline = Instant::now() + Duration::from_secs(2);
            let shared = &(*client).shared;
            while shared.rejections.load(Ordering::Relaxed) == 0 {
                assert!(Instant::now() < deadline);
                thread::yield_now();
            }
            while !*lock(&(*client).control).stop.as_ref().unwrap().borrow() {
                assert!(Instant::now() < deadline);
                thread::yield_now();
            }
            resume.send(()).unwrap();
            let deadline = Instant::now() + Duration::from_secs(2);
            while errors.load(Ordering::SeqCst) == 0 {
                assert!(Instant::now() < deadline);
                thread::yield_now();
            }
            assert_eq!(ttl_live_client_disconnect(client), ResultCode::Ok);
            let shared = &(*client).shared;
            assert_eq!(
                shared.received.load(Ordering::Relaxed),
                shared.dispatched.load(Ordering::Relaxed)
                    + shared.discarded.load(Ordering::Relaxed)
            );
            assert_eq!(errors.load(Ordering::SeqCst), 1);
            assert!(lock(&shared.dispatch).sender.is_none());
            ttl_live_client_destroy(client);
        }
    }

    #[test]
    fn clearing_callback_waits_for_inflight_userdata_then_stops_delivery() {
        unsafe {
            let client = ttl_live_client_create(&options());
            assert!(!client.is_null());
            let (entered, rx) = mpsc::channel();
            let (resume, r) = mpsc::channel();
            let blocking = Box::new(Blocking {
                entered,
                resume: Mutex::new(r),
            });
            ttl_live_client_set_event_callback(
                client,
                Some(block_event),
                (&*blocking as *const Blocking).cast_mut().cast(),
            );
            start_replay(client);
            rx.recv_timeout(Duration::from_secs(1)).unwrap();
            let (done, wait) = mpsc::channel();
            let address = client as usize;
            let clearer = thread::spawn(move || {
                let result =
                    ttl_live_client_set_event_callback(address as *mut _, None, ptr::null_mut());
                done.send(result).unwrap();
            });
            assert!(wait.recv_timeout(Duration::from_millis(20)).is_err());
            resume.send(()).unwrap();
            assert_eq!(
                wait.recv_timeout(Duration::from_secs(1)).unwrap(),
                ResultCode::Ok
            );
            clearer.join().unwrap();
            drop(blocking);
            let started = Instant::now();
            ttl_live_client_destroy(client);
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }
    unsafe extern "C" fn stop_in_callback(data: *mut std::ffi::c_void, _: *const NativeEvent) {
        let client = data as *mut NativeClient;
        ttl_live_client_destroy(client);
        assert_eq!(ttl_last_error_code(), ResultCode::InvalidState);
        assert_eq!(ttl_live_client_disconnect(client), ResultCode::Ok);
    }
    #[test]
    fn callback_disconnect_is_reentrant_and_destroy_is_refused_on_worker() {
        for capacity in [0, 8] {
            unsafe {
                let client = ttl_live_client_create(&options());
                assert!(!client.is_null());
                assert_eq!(
                    ttl_live_client_set_async_delivery(client, capacity),
                    ResultCode::Ok
                );
                ttl_live_client_set_event_callback(client, Some(stop_in_callback), client.cast());
                start_replay(client);
                // Worker requests cancellation through the production API.
                let start = Instant::now();
                loop {
                    if lock(&(*client).control)
                        .worker
                        .as_ref()
                        .unwrap()
                        .is_finished()
                    {
                        break;
                    }
                    assert!(start.elapsed() < Duration::from_secs(1));
                    thread::yield_now();
                }
                ttl_live_client_destroy(client);
            }
        }
    }
    #[test]
    #[ignore = "offline 30-minute resource soak; set TTL_SOAK_SECONDS for a shorter smoke"]
    fn offline_callback_lifecycle_soak() {
        fn memory(label: &str) {
            if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
                let lines: Vec<_> = status
                    .lines()
                    .filter(|line| {
                        line.starts_with("VmRSS:")
                            || line.starts_with("VmHWM:")
                            || line.starts_with("Threads:")
                    })
                    .collect();
                println!("{label}: {}", lines.join(" "));
            }
        }
        let seconds: u64 = std::env::var("TTL_SOAK_SECONDS").map_or(1800, |v| v.parse().unwrap());
        assert!((3..=3600).contains(&seconds));
        memory("baseline");
        for rate in [1000, 5000, 20000] {
            let start = Instant::now();
            let mut cycles: u64 = 0;
            let mut total = 0;
            while start.elapsed().as_secs() < seconds / 3 {
                unsafe {
                    let client = ttl_live_client_create(&options());
                    assert!(!client.is_null());
                    assert_eq!(
                        ttl_live_client_set_async_delivery(client, 64),
                        ResultCode::Ok
                    );
                    let count = AtomicUsize::new(0);
                    let overflows = AtomicUsize::new(0);
                    ttl_live_client_set_event_callback(
                        client,
                        Some(count_event),
                        (&count as *const AtomicUsize).cast_mut().cast(),
                    );
                    ttl_live_client_set_error_callback(
                        client,
                        Some(count_overflow),
                        (&overflows as *const AtomicUsize).cast_mut().cast(),
                    );
                    start_replay_rate(client, rate);
                    thread::sleep(Duration::from_secs(1));
                    assert_eq!(ttl_live_client_disconnect(client), ResultCode::Ok);
                    let before = count.load(Ordering::SeqCst);
                    let shared = &(*client).shared;
                    assert_eq!(
                        shared.received.load(Ordering::Relaxed),
                        shared.dispatched.load(Ordering::Relaxed)
                            + shared.discarded.load(Ordering::Relaxed)
                    );
                    ttl_live_client_destroy(client);
                    assert_eq!(count.load(Ordering::SeqCst), before);
                    total += before;
                }
                cycles += 1;
                if cycles.is_multiple_of(10) {
                    memory(&format!(
                        "rate_target={rate} cycles={cycles} events={total}"
                    ));
                }
            }
            memory(&format!("cleanup rate_target={rate} cycles={cycles} events={total} actual_events_per_second={:.0}", total as f64 / start.elapsed().as_secs_f64()));
        }
    }

    #[test]
    fn struct_size_and_bad_bundle_fail_without_network() {
        unsafe {
            let mut o = options();
            o.struct_size = 4;
            assert!(ttl_live_client_create(&o).is_null());
            assert_eq!(ttl_last_error_code(), ResultCode::InvalidArgument);
            let mut o = options();
            o.bundle = c"not javascript".as_ptr();
            o.bundle_len = 14;
            assert!(ttl_live_client_create(&o).is_null());
            assert_eq!(ttl_last_error_code(), ResultCode::Signer);
            // A base v1 allocation need not include the appended cookie fields.
            #[repr(C)]
            struct Base {
                size: u32,
                id: *const c_char,
                id_len: usize,
                bundle: *const c_char,
                bundle_len: usize,
                reconnect: bool,
                retries: u32,
            }
            let b = Base {
                size: std::mem::size_of::<Base>() as u32,
                id: c"test".as_ptr(),
                id_len: 4,
                bundle: MOCK_BUNDLE.as_ptr().cast(),
                bundle_len: MOCK_BUNDLE.len(),
                reconnect: false,
                retries: 0,
            };
            let c = ttl_live_client_create((&b as *const Base).cast());
            assert!(!c.is_null());
            ttl_live_client_destroy(c);
        }
    }
}
