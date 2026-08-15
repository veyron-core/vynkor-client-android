//! Per-capability handlers.
//!
//! Each capability = one `device.<cap>` plugin registered on the host
//! (plugin_id = "<device_id>.<cap>"), backed by a Kotlin provider (ffi.rs).
//! The dispatch table maps an inbound `target` to its handler.

/// A registered capability the agent can route frames to.
pub trait Capability: Send + Sync {
    /// Capability name, e.g. "geo" — becomes plugin_id "<device_id>.geo".
    fn name(&self) -> &'static str;
}

// TODO(caps): battery / geo / notifications / clipboard / contacts handlers
// wired to the Kotlin providers; mic (PCM -> Opus, streaming) and speaker
// (Opus -> PCM via SpeakerSink) raw-binary audio path.
