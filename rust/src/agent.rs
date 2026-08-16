//! The `Agent` object — Kotlin's handle on the Rust core.
//!
//! Owns a dedicated tokio runtime on a background thread, one reconnect loop
//! per capability (each a `CapConn` to the host), the capability-provider
//! slots, and the Kotlin→Rust push paths. No Android APIs here — Kotlin
//! implements the foreign traits (ffi.rs), Rust runs the protocol.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread::JoinHandle;

use prost::Message;
use tokio::sync::{mpsc, watch};
use veyron_wire::framing::FLAG_RAW_BINARY;
use veyron_wire::proto::veyron::{envelope, Envelope};

use crate::caps;
use crate::error::AgentError;
use crate::ffi::{
    AgentConfig, BatteryProvider, ClipboardProvider, ContactsProvider, Location, LocationProvider,
    SpeakerSink,
};
use crate::protocol::{build_frame, is_kernel_routed, target_str, Frame};
use crate::transport::{CapConn, RegisterParams, BACKOFF_INITIAL, BACKOFF_MAX};

/// Lifecycle state; `is_connected()` is the only part visible to Kotlin.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AgentState {
    Stopped,
    Starting,
    Connected,
    Disconnected,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// Frames the push paths queue onto a live connection; the per-cap write loop
/// drains the channel and MACs each frame with the session key.
#[derive(Debug)]
pub struct Outbound {
    pub frame: Frame,
}

/// Rust-owned agent: created by Kotlin, then capability providers (foreign
/// traits) are registered and the lifecycle is driven from Kotlin.
#[derive(uniffi::Object)]
pub struct Agent {
    config: AgentConfig,
    state: Mutex<AgentState>,
    shutdown: AtomicBool,
    stop_tx: Mutex<Option<watch::Sender<bool>>>,
    runtime: Mutex<Option<JoinHandle<()>>>,
    battery: Mutex<Option<Arc<dyn BatteryProvider>>>,
    location: Mutex<Option<Arc<dyn LocationProvider>>>,
    clipboard: Mutex<Option<Arc<dyn ClipboardProvider>>>,
    contacts: Mutex<Option<Arc<dyn ContactsProvider>>>,
    speaker: Mutex<Option<Arc<dyn SpeakerSink>>>,
    /// live outbound channels per capability, for the push paths
    caps: Mutex<HashMap<String, mpsc::Sender<Outbound>>>,
    live: AtomicUsize,
}

#[uniffi::export]
impl Agent {
    #[uniffi::constructor]
    pub fn new(config: AgentConfig) -> Self {
        init_tracing();
        Self {
            config,
            state: Mutex::new(AgentState::Stopped),
            shutdown: AtomicBool::new(false),
            stop_tx: Mutex::new(None),
            runtime: Mutex::new(None),
            battery: Mutex::new(None),
            location: Mutex::new(None),
            clipboard: Mutex::new(None),
            contacts: Mutex::new(None),
            speaker: Mutex::new(None),
            caps: Mutex::new(HashMap::new()),
            live: AtomicUsize::new(0),
        }
    }

    // ---- lifecycle ----

    /// Connect to the host: spawn the tokio runtime + one reconnect loop per
    /// capability on a background thread. Returns immediately.
    pub fn start(self: Arc<Self>) {
        if *lock(&self.state) != AgentState::Stopped {
            tracing::warn!("agent already running");
            return;
        }
        let me = Arc::clone(&self);
        let handle = std::thread::Builder::new()
            .name("vynkor-agent".into())
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .worker_threads(2)
                    .thread_name("vynkor-agent-rt")
                    .build()
                    .expect("tokio runtime");
                rt.block_on(me.run());
            })
            .expect("spawn agent thread");
        *lock(&self.runtime) = Some(handle);
        *lock(&self.state) = AgentState::Starting;
    }

    /// Gracefully stop all connections and the runtime thread.
    pub fn stop(&self) {
        self.shutdown.store(true, Ordering::SeqCst);
        if let Some(tx) = lock(&self.stop_tx).take() {
            let _ = tx.send(true);
        }
        if let Some(handle) = lock(&self.runtime).take() {
            let _ = handle.join();
        }
        *lock(&self.state) = AgentState::Stopped;
    }

    pub fn is_connected(&self) -> bool {
        self.live.load(Ordering::Relaxed) > 0
    }

    /// True once `start()` has been called (regardless of connection state).
    pub fn is_started(&self) -> bool {
        *lock(&self.state) != AgentState::Stopped
    }

    // ---- capability providers (Kotlin implements, Rust pulls) ----

    pub fn set_battery(&self, p: Arc<dyn BatteryProvider>) {
        *lock(&self.battery) = Some(p);
    }

    pub fn set_location(&self, p: Arc<dyn LocationProvider>) {
        *lock(&self.location) = Some(p);
    }

    pub fn set_clipboard(&self, p: Arc<dyn ClipboardProvider>) {
        *lock(&self.clipboard) = Some(p);
    }

    pub fn set_contacts(&self, p: Arc<dyn ContactsProvider>) {
        *lock(&self.contacts) = Some(p);
    }

    pub fn set_speaker(&self, p: Arc<dyn SpeakerSink>) {
        *lock(&self.speaker) = Some(p);
    }

    // ---- Kotlin -> Rust push paths (event-driven capabilities) ----

    /// PCM from the mic (Kotlin `AudioRecord`, 16 kHz mono s16le). v1: PCM
    /// passthrough (`AUDIO_CODEC_PCM_S16LE`); Opus encode is a follow-up.
    pub fn push_mic_pcm(&self, pcm: Vec<u8>) {
        let Some(tx) = self.live_channel("mic") else {
            tracing::warn!("mic: no live connection, dropping {} bytes", pcm.len());
            return;
        };
        let chunk = veyron_wire::proto::veyron::AudioStreamChunk {
            stream_id: 0,
            codec: veyron_wire::proto::veyron::AudioCodec::PcmS16le as i32,
            sample_rate: 16_000,
            channels: 1,
            data: pcm,
            end_of_stream: false,
        };
        let env = Envelope {
            payload: Some(envelope::Payload::AudioStreamChunk(chunk)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "stt");
    }

    /// A notification arrived (Kotlin `NotificationListenerService`) — publish
    /// it as a device event on the host.
    pub fn on_notification(&self, app: String, title: String, body: String) {
        let Some(tx) = self.live_channel("notifications") else {
            tracing::warn!("notifications: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({ "app": app, "title": title, "body": body });
        let ev = veyron_wire::proto::veyron::EventPublish {
            event_type: "notification".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }

    /// The clipboard changed on the device — publish as a device event.
    pub fn on_clipboard_change(&self, text: String) {
        let Some(tx) = self.live_channel("clipboard") else {
            tracing::warn!("clipboard: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({ "text": text });
        let ev = veyron_wire::proto::veyron::EventPublish {
            event_type: "clipboard_changed".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }

    /// A slow location fix is ready (push path) — publish as a device event.
    pub fn push_geo_update(&self, loc: Location) {
        let Some(tx) = self.live_channel("geo") else {
            tracing::warn!("geo: no live connection, dropping");
            return;
        };
        let payload = serde_json::json!({
            "lat": loc.lat,
            "lon": loc.lon,
            "accuracy_m": loc.accuracy_m,
        });
        let ev = veyron_wire::proto::veyron::EventPublish {
            event_type: "geo_update".into(),
            payload_json: serde_json::to_vec(&payload).unwrap_or_default(),
        };
        let env = Envelope {
            payload: Some(envelope::Payload::EventPublish(ev)),
            ..Default::default()
        };
        self.send_raw_frame(&tx, env, "kernel");
    }
}

impl Agent {
    fn register_params(&self, cap: &str) -> RegisterParams {
        RegisterParams {
            device_id: self.config.device_id.clone(),
            cap: cap.to_string(),
            jwt_token: self.config.jwt_token.clone(),
            jwt_secret: (!self.config.jwt_secret.is_empty())
                .then(|| self.config.jwt_secret.clone()),
            os_version: self.config.os_version.clone(),
            arch: self.config.arch.clone(),
            user_id: self.config.user_id.clone(),
        }
    }

    async fn run(self: Arc<Self>) {
        let (stop_tx, stop_rx) = watch::channel(false);
        *lock(&self.stop_tx) = Some(stop_tx);
        for cap in self.config.capabilities.clone() {
            spawn_cap_loop(Arc::clone(&self), cap, stop_rx.clone());
        }
        // keep the runtime alive until stop(); cap loops exit via the watch
        let mut rx = stop_rx;
        while !*rx.borrow() {
            if rx.changed().await.is_err() {
                break;
            }
        }
    }

    fn live_channel(&self, cap: &str) -> Option<mpsc::Sender<Outbound>> {
        lock(&self.caps).get(cap).cloned()
    }

    fn send_raw_frame(&self, tx: &mpsc::Sender<Outbound>, env: Envelope, target: &str) {
        let mut payload = Vec::new();
        if env.encode(&mut payload).is_err() {
            tracing::error!(target, "encode push frame");
            return;
        }
        let frame = build_frame(target, 0, payload);
        let _ = tx.try_send(Outbound { frame });
    }

    // ---- provider accessors (caps dispatch) ----

    pub(crate) fn battery_provider(&self) -> Option<Arc<dyn BatteryProvider>> {
        lock(&self.battery).clone()
    }

    pub(crate) fn location_provider(&self) -> Option<Arc<dyn LocationProvider>> {
        lock(&self.location).clone()
    }

    pub(crate) fn clipboard_provider(&self) -> Option<Arc<dyn ClipboardProvider>> {
        lock(&self.clipboard).clone()
    }

    pub(crate) fn contacts_provider(&self) -> Option<Arc<dyn ContactsProvider>> {
        lock(&self.contacts).clone()
    }

    pub(crate) fn speaker_provider(&self) -> Option<Arc<dyn SpeakerSink>> {
        lock(&self.speaker).clone()
    }

    // ---- inbound dispatch ----

    /// Handle one host→device frame on a capability connection.
    async fn dispatch_inbound(&self, frame: &Frame, cap: &str, out: &mpsc::Sender<Outbound>) {
        if frame.flags & FLAG_RAW_BINARY != 0 {
            caps::audio::handle_raw_inbound(self, frame, cap);
            return;
        }
        let Ok(env) = Envelope::decode(frame.payload.as_ref()) else {
            tracing::warn!(cap, target = %target_str(frame), "undecodable inbound frame");
            return;
        };
        match env.payload {
            Some(envelope::Payload::Ping(p)) => {
                let pong = veyron_wire::proto::veyron::Pong {
                    original_timestamp: p.timestamp,
                    ..Default::default()
                };
                let resp = Envelope {
                    message_id: env.message_id,
                    payload: Some(envelope::Payload::Pong(pong)),
                    ..Default::default()
                };
                self.reply(out, resp).await;
            }
            Some(envelope::Payload::ActionRequest(req)) => {
                let resp = caps::handle_action_request(self, cap, req);
                self.reply(out, resp).await;
            }
            Some(envelope::Payload::SessionClose(_)) => {
                tracing::info!(cap, "host closed session");
            }
            _ => {
                tracing::trace!(cap, target = %target_str(frame), "unhandled inbound frame");
            }
        }
    }

    async fn reply(&self, out: &mpsc::Sender<Outbound>, env: Envelope) {
        let mut payload = Vec::new();
        if env.encode(&mut payload).is_err() {
            return;
        }
        let frame = build_frame("kernel", 0, payload);
        let _ = out.send(Outbound { frame }).await;
    }
}

fn spawn_cap_loop(agent: Arc<Agent>, cap: String, stop_rx: watch::Receiver<bool>) {
    tokio::spawn(async move {
        cap_loop(agent, cap, stop_rx).await;
    });
}

/// Install the tracing subscriber once (no-op on repeat calls) so protocol
/// logs reach logcat. Android has no default tracing writer; tracing-android
/// routes events to `__android_log_write` (visible via `adb logcat`). On the
/// host the events go to stderr instead.
fn init_tracing() {
    use std::sync::Once;
    use tracing_subscriber::layer::SubscriberExt;
    use tracing_subscriber::util::SubscriberInitExt;
    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let filter = tracing_subscriber::EnvFilter::try_from_default_env()
            .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("debug"));
        let subscriber = tracing_subscriber::registry().with(filter);
        #[cfg(target_os = "android")]
        if let Ok(layer) = tracing_android::layer("vynkor") {
            let _ = subscriber.with(layer).try_init();
        }
        #[cfg(not(target_os = "android"))]
        let _ = subscriber
            .with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr))
            .try_init();
    });
}

/// One capability's reconnect loop: connect → register → read/write until the
/// connection dies or the agent shuts down, then backoff and retry.
async fn cap_loop(agent: Arc<Agent>, cap: String, mut stop_rx: watch::Receiver<bool>) {
    let mut backoff = BACKOFF_INITIAL;
    loop {
        if agent.shutdown.load(Ordering::SeqCst) || *stop_rx.borrow() {
            return;
        }
        match one_cycle(agent.clone(), cap.clone()).await {
            Ok(()) => tracing::info!(cap, "connection closed"),
            Err(AgentError::Shutdown) => return,
            Err(e) => tracing::warn!(cap, error = %e, "connection failed"),
        }
        // poll shutdown during the backoff sleep so stop() is responsive
        let deadline = tokio::time::Instant::now() + backoff;
        loop {
            tokio::select! {
                _ = tokio::time::sleep_until(deadline) => break,
                changed = stop_rx.changed() => {
                    if changed.is_err() || *stop_rx.borrow() { return; }
                }
            }
        }
        backoff = (backoff * 2).min(BACKOFF_MAX);
    }
}

/// One connect-register-loop pass for a capability.
async fn one_cycle(agent: Arc<Agent>, cap: String) -> Result<(), AgentError> {
    let params = agent.register_params(&cap);
    let conn = CapConn::connect_and_register(&agent.config.host_url, &params).await?;
    let session_key = conn.session_key();
    let (mut read, mut write, _) = conn.into_parts();

    let (out_tx, out_rx) = mpsc::channel::<Outbound>(64);
    {
        let mut caps = lock(&agent.caps);
        caps.insert(cap.clone(), out_tx.clone());
    }
    agent.live.fetch_add(1, Ordering::Relaxed);
    *lock(&agent.state) = AgentState::Connected;

    // write loop: drains push-path + reply frames, MACs, sends
    let write_task = tokio::spawn(async move {
        use futures_util::SinkExt;
        let mut rx = out_rx;
        while let Some(Outbound { mut frame }) = rx.recv().await {
            if let Some(key) = &session_key {
                crate::protocol::arm_mac(&mut frame, key);
            }
            if write
                .send(tokio_tungstenite::tungstenite::Message::Binary(
                    crate::protocol::frame_to_bytes(&frame).into(),
                ))
                .await
                .is_err()
            {
                break;
            }
        }
    });

    let result = loop {
        use futures_util::StreamExt;
        let frame = match read.next().await {
            Some(Ok(tokio_tungstenite::tungstenite::Message::Binary(data))) => {
                let mut f = crate::protocol::parse_frame(&data)?;
                crate::protocol::verify_inbound(&mut f, session_key.as_ref())?;
                f
            }
            Some(Ok(tokio_tungstenite::tungstenite::Message::Close(_))) | None => {
                break Err(AgentError::Ws("websocket closed".into()));
            }
            Some(Ok(_)) => continue,
            Some(Err(e)) => break Err(AgentError::from(e)),
        };
        if is_kernel_routed(&frame) {
            agent.dispatch_inbound(&frame, &cap, &out_tx).await;
        } else {
            tracing::trace!(cap, target = %target_str(&frame), "device-traffic frame");
        }
    };

    write_task.abort();
    agent.live.fetch_sub(1, Ordering::Relaxed);
    lock(&agent.caps).remove(&cap);
    if agent.live.load(Ordering::Relaxed) == 0 {
        *lock(&agent.state) = AgentState::Disconnected;
    }
    result
}
