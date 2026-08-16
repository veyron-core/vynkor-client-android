//! Raw-binary audio path (FLAG_RAW_BINARY frames): host TTS → speaker sink,
//! and the mic → host STT direction (push_mic_pcm on the Agent).

use prost::Message;
use veyron_wire::framing::Frame;
use veyron_wire::proto::veyron::{envelope, AudioCodec, AudioStreamChunk, Envelope};

use crate::agent::Agent;

/// Host→device: decode an AudioStreamChunk and hand the PCM to the speaker
/// sink. v1 supports PCM_S16LE passthrough; OPUS decode is a follow-up (the
/// design defers the codec crate choice).
pub fn handle_raw_inbound(agent: &Agent, frame: &Frame, cap: &str) {
    if cap != "speaker" {
        tracing::warn!(cap, "raw audio to non-speaker capability, dropping");
        return;
    }
    let Some(sink) = agent.speaker_provider() else {
        tracing::warn!("speaker: no sink registered, dropping audio");
        return;
    };
    let Ok(env) = Envelope::decode(frame.payload.as_ref()) else {
        tracing::warn!("speaker: undecodable raw frame, dropping");
        return;
    };
    let Some(envelope::Payload::AudioStreamChunk(chunk)) = env.payload else {
        tracing::warn!("speaker: raw frame is not an AudioStreamChunk, dropping");
        return;
    };
    match chunk.codec {
        c if c == AudioCodec::PcmS16le as i32 => sink.play_pcm(chunk.data),
        c => tracing::warn!(codec = %audio_codec_name(c), "speaker: unsupported codec, dropping"),
    }
}

fn audio_codec_name(codec: i32) -> &'static str {
    match codec {
        c if c == AudioCodec::PcmS16le as i32 => "pcm_s16le",
        c if c == AudioCodec::Opus as i32 => "opus",
        _ => "unknown",
    }
}

/// Build the FLAG_RAW_BINARY frame for a mic chunk (used by the host STT
/// path). Kept here so the audio frame layout lives next to the decoder.
pub fn mic_chunk_frame(target: &str, stream_id: u32, pcm: Vec<u8>) -> Frame {
    let chunk = AudioStreamChunk {
        stream_id,
        codec: AudioCodec::PcmS16le as i32,
        sample_rate: 16_000,
        channels: 1,
        data: pcm,
        end_of_stream: false,
    };
    let env = Envelope {
        payload: Some(envelope::Payload::AudioStreamChunk(chunk)),
        ..Default::default()
    };
    let mut payload = Vec::new();
    let _ = env.encode(&mut payload);
    crate::protocol::build_frame(target, veyron_wire::framing::FLAG_RAW_BINARY, payload)
}
