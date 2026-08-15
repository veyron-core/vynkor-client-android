//! WS transport to the host kernel.
//!
//! v1 skeleton: contract + TODO. Mirrors the kernel bridge
//! (`veyron/src/bridge/mod.rs`): one WS connection per capability, JWT in
//! `Sec-WebSocket-Protocol: veyron, <token>`, exponential backoff, TLS pinned
//! to the host's served certificate (D-07 rule).

// TODO(transport):
// - connect_ws(host_url, jwt) per capability (tokio-tungstenite + rustls)
// - read/write loops: frame <-> WS binary message, one frame per message
// - reconnect + backoff (mirror BRIDGE_MAX_BACKOFF)
// - FLAG_RAW_BINARY audio frames pass unchanged (mic/speaker)
