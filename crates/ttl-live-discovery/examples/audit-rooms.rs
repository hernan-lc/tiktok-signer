//! Observe many distinct real LIVE rooms with one warm signer, bounded concurrency and private capture.
//! TTL_AUDIT_ROOMS=10 TTL_AUDIT_CONCURRENCY=10 TTL_LISTEN_SECONDS=60 TTL_BUNDLE=...
//! TTL_AUDIT_OUTPUT=.local/scan-new cargo run -p ttl-live-discovery --example audit-rooms
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicUsize, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};
use ttl_live_discovery::{DiscoveryClient, DiscoveryError, UrlSigner};
use ttl_live_events::{AuditReport, LiveEvent};
use ttl_live_ws::{ConnectConfig, ReconnectPolicy, ReconnectingConnection, StreamError};
use ttl_sign_core::{
    backend::SignerBackend, CookieJar, DevicePreset, LocationPreset, Preset, ScreenPreset,
};
use ttl_sign_embedded::{EmbeddedSigner, Profile};
use ttl_sign_headless::{HeadlessBackend, HeadlessConfig, TRANSPORT_PRODUCT};

fn setting(name: &str, default: usize, max: usize) -> usize {
    let value = std::env::var(name)
        .map(|v| v.parse().expect("integer setting"))
        .unwrap_or(default);
    assert!((1..=max).contains(&value), "invalid {name}");
    value
}
fn session() -> CookieJar {
    let path = std::env::var_os("TTL_SESSION_FILE")
        .map(PathBuf::from)
        .or_else(|| {
            let base = std::env::var_os("XDG_CONFIG_HOME")
                .map(PathBuf::from)
                .or_else(|| {
                    std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config"))
                })?;
            Some(base.join("ttl-signer/session"))
        });
    path.and_then(|path| std::fs::read_to_string(path).ok())
        .map(|raw| CookieJar::parse(raw.trim()))
        .unwrap_or_default()
}
fn private_dir(path: &Path) -> std::io::Result<()> {
    std::fs::create_dir(path)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}
fn private_write(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    let mut options = std::fs::OpenOptions::new();
    options.create_new(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path)?.write_all(bytes)
}
struct Budget {
    bytes: AtomicUsize,
    files: AtomicUsize,
}
impl Budget {
    fn new(bytes: usize, files: usize) -> Self {
        Self {
            bytes: AtomicUsize::new(bytes),
            files: AtomicUsize::new(files),
        }
    }
    #[allow(deprecated)] // workspace MSRV supports fetch_update
    fn reserve(&self, bytes: usize) -> bool {
        if self
            .files
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |left| {
                left.checked_sub(1)
            })
            .is_err()
        {
            return false;
        }
        if self
            .bytes
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |left| {
                left.checked_sub(bytes)
            })
            .is_err()
        {
            self.files.fetch_add(1, Ordering::AcqRel);
            return false;
        }
        true
    }
    fn release(&self, bytes: usize) {
        self.bytes.fetch_add(bytes, Ordering::AcqRel);
        self.files.fetch_add(1, Ordering::AcqRel);
    }
}
struct CaptureBudgets {
    baseline: Budget,
    novel: Budget,
    emotes: Budget,
}
impl Default for CaptureBudgets {
    fn default() -> Self {
        Self {
            baseline: Budget::new(32 * 1024 * 1024, 4096),
            novel: Budget::new(32 * 1024 * 1024, 4096),
            emotes: Budget::new(64 * 1024 * 1024, 4096),
        }
    }
}
fn stream_error(error: &StreamError) -> &'static str {
    match error {
        StreamError::Refused(_) => "refused",
        StreamError::Signer(_) => "signer",
        StreamError::Connect(_) => "connect",
        StreamError::Exhausted { .. } => "stream",
    }
}
async fn observe(
    backend: Arc<dyn SignerBackend>,
    room: String,
    seconds: usize,
    root: PathBuf,
    budgets: Arc<CaptureBudgets>,
) -> serde_json::Value {
    let started = Instant::now();
    let directory = root.join(format!("room-{room}"));
    if private_dir(&directory).is_err() {
        return serde_json::json!({"room_id":room,"status":"capture_directory_error"});
    }
    let mut status = "ok";
    let mut frames = 0;
    let mut decode_errors = 0;
    let mut captured = 0;
    let mut capture_errors = 0;
    let mut evidence_not_retained = 0;
    let mut baseline_not_retained = 0;
    let mut report = AuditReport::default();
    let mut emote_candidates = 0;
    let mut novel = BTreeSet::new();
    let opened = tokio::time::timeout(
        Duration::from_secs(30),
        ReconnectingConnection::open(
            backend,
            &room,
            ConnectConfig::default(),
            ReconnectPolicy::none(),
        ),
    )
    .await;
    match opened {
        Ok(Ok(mut connection)) => {
            let deadline = Instant::now() + Duration::from_secs(seconds as u64);
            loop {
                let remaining = deadline.saturating_duration_since(Instant::now());
                if remaining.is_zero() {
                    break;
                }
                let message = tokio::time::timeout(remaining, connection.next_message()).await;
                let message = match message {
                    Err(_) => break,
                    Ok(Some(Ok(message))) => message,
                    Ok(Some(Err(error))) => {
                        status = stream_error(&error);
                        break;
                    }
                    Ok(None) => {
                        status = "closed";
                        break;
                    }
                };
                frames += 1;
                let mut interesting = false;
                let mut emote_batch = false;
                match ttl_live_events::decode_batch(&message.payload) {
                    Ok(batch) => {
                        report.observe(&batch);
                        for event in &batch.events {
                            if event.method().len() > 256 {
                                continue;
                            }
                            let raw = event
                                .wire_emote_entries()
                                .is_some_and(|(count, _)| count > 0);
                            let normalized = match &event.event {
                                LiveEvent::Chat(chat) => !chat.emotes.is_empty(),
                                LiveEvent::EmoteChat(_) => true,
                                _ => false,
                            };
                            let method = event.method().to_ascii_lowercase();
                            let candidate = raw
                                || normalized
                                || ["emote", "emoji", "sticker"]
                                    .iter()
                                    .any(|name| method.contains(name));
                            if candidate {
                                emote_candidates += 1;
                                emote_batch = true;
                                interesting = true;
                            }
                            let diagnostic = event.diagnostics();
                            let mut signatures = Vec::new();
                            if !event.schema.is_known() {
                                signatures.push(format!("{}:unknown_method", event.method()));
                            }
                            for field in diagnostic.unknown_fields.iter().take(32) {
                                signatures.push(format!(
                                    "{}:{:?}:wire{}",
                                    event.method(),
                                    field.path,
                                    field.wire_type
                                ));
                            }
                            for field in diagnostic.unknown_enums.iter().take(32) {
                                signatures.push(format!(
                                    "{}:{:?}:enum={}",
                                    event.method(),
                                    field.path,
                                    field.number
                                ));
                            }
                            if diagnostic.schema_warning {
                                signatures.push(format!("{}:warning", event.method()));
                            }
                            if !matches!(
                                diagnostic.normalization,
                                ttl_live_events::NormalizationStatus::Normalized
                                    | ttl_live_events::NormalizationStatus::UnsupportedMethod
                            ) {
                                signatures.push(format!(
                                    "{}:{:?}",
                                    event.method(),
                                    diagnostic.normalization
                                ));
                            }
                            for signature in signatures {
                                if event.method().len() <= 256
                                    && novel.len() < 128
                                    && novel.insert(signature)
                                {
                                    interesting = true;
                                }
                            }
                        }
                    }
                    Err(_) => {
                        decode_errors += 1;
                        interesting = true;
                    }
                }
                let budget = if emote_batch {
                    Some(&budgets.emotes)
                } else if interesting {
                    Some(&budgets.novel)
                } else if frames <= 3 {
                    Some(&budgets.baseline)
                } else {
                    None
                };
                if let Some(budget) = budget {
                    if budget.reserve(message.payload.len()) {
                        if private_write(
                            &directory.join(format!("batch-{frames:06}.pb")),
                            &message.payload,
                        )
                        .is_ok()
                        {
                            captured += 1;
                        } else {
                            budget.release(message.payload.len());
                            capture_errors += 1;
                        }
                    } else if interesting {
                        evidence_not_retained += 1;
                    } else {
                        baseline_not_retained += 1;
                    }
                }
            }
            let _ = tokio::time::timeout(Duration::from_secs(1), connection.close()).await;
        }
        Ok(Err(error)) => status = stream_error(&error),
        Err(_) => status = "open_timeout",
    }
    if status == "ok" && decode_errors > 0 {
        status = "decode_error";
    }
    if status == "ok" && report.events == 0 {
        status = "quiet";
    }
    let result = serde_json::json!({"room_id":room,"status":status,"elapsed_seconds":started.elapsed().as_secs_f64(),
        "listen_seconds_requested":seconds,"frames":frames,"audit":report,"decode_errors":decode_errors,
        "emote_candidates":emote_candidates,"captured_frames":captured,"capture_errors":capture_errors,
        "evidence_not_retained":evidence_not_retained,"baseline_not_retained":baseline_not_retained});
    if private_write(
        &directory.join("audit.json"),
        &serde_json::to_vec_pretty(&result).unwrap(),
    )
    .is_err()
    {
        eprintln!("room report write failed");
    }
    result
}
#[tokio::main(flavor = "multi_thread", worker_threads = 4)]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let target = setting("TTL_AUDIT_ROOMS", 10, 1000);
    let concurrency = setting("TTL_AUDIT_CONCURRENCY", 10, 128);
    let seconds = setting("TTL_LISTEN_SECONDS", 60, 3600);
    let root = PathBuf::from(std::env::var("TTL_AUDIT_OUTPUT")?);
    private_dir(&root)?;
    let preset = Preset::new(
        DevicePreset::chrome_linux(),
        LocationPreset::us_east(),
        ScreenPreset::FHD,
    );
    let jar = session();
    if jar.is_empty() {
        return Err("configured session required".into());
    }
    let client = DiscoveryClient::new(&preset)?.with_session(jar.to_cookie_string());
    let keywords = std::env::var("TTL_AUDIT_KEYWORDS")
        .unwrap_or_else(|_| "live,gaming,minecraft,roblox,music,battle,anime,chat".into());
    let mut rooms = VecDeque::new();
    let mut seen = BTreeSet::new();
    let mut pages = 0;
    let mut discovery_errors = Vec::new();
    'discovery: for keyword in keywords
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .take(32)
    {
        let mut cursor = None;
        for _page in 0..50 {
            if seen.len() >= target {
                break 'discovery;
            }
            if pages > 0 {
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            pages += 1;
            match client.live_channels_context(keyword, cursor.as_ref()).await {
                Ok(page) => {
                    let found = page.rooms;
                    cursor = page.next;
                    if found.is_empty() && cursor.is_none() {
                        break;
                    }
                    let before = seen.len();
                    for room in found {
                        if seen.len() >= target {
                            break;
                        }
                        if !room.room_id.is_empty()
                            && room.room_id.bytes().all(|b| b.is_ascii_digit())
                            && seen.insert(room.room_id.clone())
                        {
                            rooms.push_back(room.room_id);
                        }
                    }
                    if cursor.is_none() || seen.len() == before {
                        break;
                    }
                }
                Err(error) => {
                    let label = match error {
                        DiscoveryError::Status { status } => format!("http_{status}"),
                        DiscoveryError::Decode(message) => format!("decode: {message}"),
                        DiscoveryError::Refused(_) => "upstream_refused".into(),
                        DiscoveryError::EmptyResponse => "empty_response".into(),
                        _ => "discovery_error".into(),
                    };
                    discovery_errors.push(label);
                    break 'discovery; // stop on refusal instead of rotating queries or identities
                }
            }
        }
    }
    private_write(
        &root.join("discovery.json"),
        &serde_json::to_vec_pretty(
            &serde_json::json!({"requested":target,"distinct_rooms":rooms,"pages":pages,"errors":discovery_errors}),
        )?,
    )?;
    println!(
        "discovered={} requested={target} pages={pages} concurrency={concurrency}",
        rooms.len()
    );
    if rooms.is_empty() {
        return Err("no rooms discovered; inspect discovery.json".into());
    }
    let signing_product =
        std::env::var("TTL_AUDIT_SIGNING_PRODUCT").unwrap_or_else(|_| "ws".into());
    let product = match signing_product.as_str() {
        "ws" => TRANSPORT_PRODUCT,
        "frontier" => ttl_live_discovery::SigningProduct::FrontierSign,
        _ => return Err("TTL_AUDIT_SIGNING_PRODUCT must be ws or frontier".into()),
    };
    let source = std::fs::read_to_string(std::env::var("TTL_BUNDLE")?)?;
    let signer: Box<dyn UrlSigner> = Box::new(EmbeddedSigner::with_product(
        source,
        Profile {
            user_agent: Some(preset.user_agent()),
            cookie: Some(jar.to_cookie_string()),
            ..Profile::default()
        },
        product,
    )?);
    let backend: Arc<dyn SignerBackend> = Arc::new(HeadlessBackend::new(
        HeadlessConfig::new(preset, jar),
        signer,
    )?);
    let budgets = Arc::new(CaptureBudgets::default());
    let mut tasks = tokio::task::JoinSet::new();
    let mut results = Vec::new();
    while !rooms.is_empty() || !tasks.is_empty() {
        while tasks.len() < concurrency {
            let Some(room) = rooms.pop_front() else {
                break;
            };
            tasks.spawn(observe(
                backend.clone(),
                room,
                seconds,
                root.clone(),
                budgets.clone(),
            ));
        }
        if let Some(result) = tasks.join_next().await {
            let result = result?;
            println!(
                "completed={} status={} frames={} events={} emote_candidates={}",
                results.len() + 1,
                result["status"],
                result["frames"],
                result["audit"]["events"],
                result["emote_candidates"]
            );
            results.push(result);
        }
    }
    let mut statuses = BTreeMap::<String, usize>::new();
    for room in &results {
        *statuses
            .entry(room["status"].as_str().unwrap_or("unknown").to_owned())
            .or_default() += 1;
    }
    let report = serde_json::json!({"schema_upstream_commit":ttl_live_events::SCHEMA_UPSTREAM_COMMIT,"signing_product":signing_product,"requested_rooms":target,"sampling_target_met":results.len()==target,"discovery_pages":pages,"discovery_errors":discovery_errors,"statuses":statuses,"results":results});
    private_write(
        &root.join("report.json"),
        &serde_json::to_vec_pretty(&report)?,
    )?;
    println!("report written; room statuses: {}", report["statuses"]);
    Ok(())
}
