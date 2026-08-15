//! The `Agent` object — the Kotlin -> Rust side of the UniFFI boundary.
//!
//! v1 skeleton: config + capability-provider slots + lifecycle state.
//! The transport/protocol wiring lands in transport.rs / protocol.rs; the
//! per-capability handlers in caps/.

use std::sync::{Arc, Mutex, MutexGuard};

use crate::ffi::{
    AgentConfig, BatteryProvider, ClipboardProvider, ContactsProvider, Location,
    LocationProvider, SpeakerSink,
};

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

/// Rust-owned agent: created by Kotlin, then capability providers (foreign
/// traits) are registered and the lifecycle is driven from Kotlin.
#[derive(uniffi::Object)]
pub struct Agent {
    config: AgentConfig,
    state: Mutex<AgentState>,
    battery: Mutex<Option<Arc<dyn BatteryProvider>>>,
    location: Mutex<Option<Arc<dyn LocationProvider>>>,
    clipboard: Mutex<Option<Arc<dyn ClipboardProvider>>>,
    contacts: Mutex<Option<Arc<dyn ContactsProvider>>>,
    speaker: Mutex<Option<Arc<dyn SpeakerSink>>>,
}

#[uniffi::export]
impl Agent {
    #[uniffi::constructor]
    pub fn new(config: AgentConfig) -> Self {
        Self {
            config,
            state: Mutex::new(AgentState::Stopped),
            battery: Mutex::new(None),
            location: Mutex::new(None),
            clipboard: Mutex::new(None),
            contacts: Mutex::new(None),
            speaker: Mutex::new(None),
        }
    }

    /// Connect to the host: spawn the WS transport + registration loops on a
    /// dedicated tokio runtime, one connection per capability.
    /// TODO(transport): see transport.rs / protocol.rs.
    pub fn start(&self) {
        tracing::info!(
            host = %self.config.host_url,
            device = %self.config.device_id,
            "agent start"
        );
        *lock(&self.state) = AgentState::Starting;
    }

    /// Gracefully stop all connections. TODO(transport): signal shutdown.
    pub fn stop(&self) {
        tracing::info!("agent stop");
        *lock(&self.state) = AgentState::Stopped;
    }

    pub fn is_connected(&self) -> bool {
        *lock(&self.state) == AgentState::Connected
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

    /// PCM from the mic (Kotlin `AudioRecord`, 16 kHz mono s16le) -> Opus ->
    /// `FLAG_RAW_BINARY` frame targeted at the host STT plugin.
    /// TODO(mic): caps/mic.rs.
    pub fn push_mic_pcm(&self, _pcm: Vec<u8>) {
        // TODO(mic): opus encode + send_raw_audio(target = "stt")
    }

    /// A notification arrived (Kotlin `NotificationListenerService`).
    /// TODO(notifications): forward to the host as a `device.notifications` event.
    pub fn on_notification(&self, _app: String, _title: String, _body: String) {
        // TODO(notifications): caps/notifications.rs
    }

    /// The clipboard changed on the device. TODO(clipboard).
    pub fn on_clipboard_change(&self, _text: String) {
        // TODO(clipboard): caps/clipboard.rs
    }

    /// A slow location fix is ready (push path). TODO(geo).
    pub fn push_geo_update(&self, _loc: Location) {
        // TODO(geo): caps/geo.rs
    }
}
