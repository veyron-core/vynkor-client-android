//! UniFFI surface — the Kotlin <-> Rust boundary.
//!
//! Kotlin implements the foreign traits (device I/O); the `Agent` object
//! (agent.rs) is Rust-owned and exported there. This file holds the plain
//! data records and the foreign traits — the contract both sides compile
//! against. No protocol logic here.

/// Connection config. `jwt_secret` is the host kernel's `jwt_secret` value —
/// needed to derive the per-session frame-MAC key (same rule as the D-06
/// bridge). Never persisted by the app.
#[derive(uniffi::Record)]
pub struct AgentConfig {
    /// Host kernel WS endpoint, e.g. `wss://host:port/ws`.
    pub host_url: String,
    /// Device JWT (`sub = device_id`, restricted claims).
    pub jwt_token: String,
    /// Host's `jwt_secret`, for frame-MAC key derivation.
    pub jwt_secret: String,
    /// Stable per-install UUID.
    pub device_id: String,
    /// Capabilities to register, e.g.
    /// `["geo", "battery", "notifications", "clipboard", "contacts", "mic", "speaker"]`.
    pub capabilities: Vec<String>,
}

/// A location fix.
#[derive(uniffi::Record)]
pub struct Location {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f32,
}

/// A contact record.
#[derive(uniffi::Record)]
pub struct Contact {
    pub name: String,
    pub phones: Vec<String>,
    pub emails: Vec<String>,
}

// ---------- foreign traits: Kotlin implements, Rust pulls ----------

/// Backend for `device.battery` — read by Rust on a host request.
#[uniffi::export(with_foreign)]
pub trait BatteryProvider: Send + Sync {
    fn level_percent(&self) -> u8;
    fn is_charging(&self) -> bool;
    fn temperature_c(&self) -> f32;
}

/// Backend for `device.geo` — Rust calls `last_known` on a host request;
/// slow fixes arrive via `Agent::push_geo_update` instead.
#[uniffi::export(with_foreign)]
pub trait LocationProvider: Send + Sync {
    fn last_known(&self) -> Option<Location>;
}

/// Backend for `device.clipboard` — read + write.
#[uniffi::export(with_foreign)]
pub trait ClipboardProvider: Send + Sync {
    fn read(&self) -> Option<String>;
    fn write(&self, text: String);
}

/// Backend for `device.contacts` — query-filtered list.
#[uniffi::export(with_foreign)]
pub trait ContactsProvider: Send + Sync {
    fn list(&self, query: String) -> Vec<Contact>;
}

/// Output for `device.speaker` — Rust hands decoded PCM (s16le mono, 16 kHz)
/// to Kotlin's AudioTrack.
#[uniffi::export(with_foreign)]
pub trait SpeakerSink: Send + Sync {
    fn play_pcm(&self, pcm: Vec<u8>);
}
