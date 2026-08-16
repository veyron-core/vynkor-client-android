//! vynkor agent-core error type.

use veyron_wire::WireError;

/// Agent failures: transport, wire, registration, and shutdown.
#[derive(Debug, thiserror::Error)]
pub enum AgentError {
    #[error("websocket connect: {0}")]
    Connect(String),
    #[error("wire: {0}")]
    Wire(#[from] WireError),
    #[error("websocket: {0}")]
    Ws(String),
    #[error("register rejected: {0}")]
    Register(String),
    #[error("capability {0}: {1}")]
    Capability(&'static str, String),
    #[error("shutdown")]
    Shutdown,
}

impl AgentError {
    /// True when the connection should be torn down and retried by the
    /// reconnect loop (vs. a fatal config error).
    pub fn is_retryable(&self) -> bool {
        !matches!(self, AgentError::Shutdown)
    }
}

impl From<tokio_tungstenite::tungstenite::Error> for AgentError {
    fn from(e: tokio_tungstenite::tungstenite::Error) -> Self {
        AgentError::Ws(e.to_string())
    }
}

impl From<prost::DecodeError> for AgentError {
    fn from(e: prost::DecodeError) -> Self {
        AgentError::Wire(WireError::Proto(e))
    }
}
