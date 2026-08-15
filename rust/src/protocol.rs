//! Wire-protocol client: registration + frame MAC.
//!
//! Reuses `veyron_wire` verbatim — `framing::{Frame, write_frame_raw,
//! read_frame, FLAG_RAW_BINARY, ...}`, `mac::{derive_session_key,
//! compute_tag, verify_tag}`, `proto::veyron::{Envelope, PluginRegister,
//! PluginRegisterAck, ...}`.

// TODO(protocol), per capability (mirrors the D-06 bridge register flow):
// 1. PluginRegister {
//      plugin_id: "<device_id>.<cap>",
//      device_id, os: DEVICE_OS_ANDROID (4), arch, os_version,
//      capabilities: [cap], protocol_version: "1.6", user_id,
//      manifest, jwt_token
//    }
// 2. PluginRegisterAck { accepted, session_nonce }
// 3. derive_session_key(jwt_secret, session_nonce, plugin_id)
// 4. FLAG_MAC_PRESENT + compute_tag on every subsequent outbound frame;
//    verify_tag on inbound.
