use anyhow::{Context, Result};
use coomi_services::{EndpointResolver, ProviderConfig, ProviderKind, ProviderProtocol};
use reqwest::multipart::{Form, Part};

pub const MAX_TRANSCRIPTION_BYTES: usize = 16 * 1024 * 1024;

pub fn protocol_for_provider(provider: &ProviderConfig) -> Option<ProviderProtocol> {
    match provider.kind {
        ProviderKind::OpenAiCompatible => Some(ProviderProtocol::OpenAiCompatible),
        ProviderKind::OpenAiResponses => Some(ProviderProtocol::OpenAiResponses),
        ProviderKind::AnthropicMessages | ProviderKind::GeminiNative | ProviderKind::DeepseekAccount => None,
    }
}

pub fn transcription_endpoint(provider: &ProviderConfig) -> Result<String> {
    anyhow::ensure!(
        matches!(provider.kind, ProviderKind::OpenAiCompatible | ProviderKind::OpenAiResponses),
        "the selected provider does not expose an OpenAI-compatible transcription API"
    );
    let base = EndpointResolver::new(&provider.base_url, ProviderProtocol::OpenAiCompatible)
        .normalized_base;
    let last = base.rsplit('/').next().unwrap_or_default().to_ascii_lowercase();
    Ok(if last == "v1" {
        format!("{base}/audio/transcriptions")
    } else {
        format!("{base}/v1/audio/transcriptions")
    })
}

pub fn first_api_key(provider: &ProviderConfig) -> Result<&str> {
    provider
        .api_keys
        .iter()
        .map(String::as_str)
        .find(|value| !value.trim().is_empty())
        .or_else(|| (!provider.api_key.trim().is_empty()).then_some(provider.api_key.as_str()))
        .context("the selected provider has no API key")
}

pub async fn transcribe(
    provider: &ProviderConfig,
    model: &str,
    language: Option<&str>,
    filename: &str,
    content_type: &str,
    bytes: Vec<u8>,
) -> Result<String> {
    anyhow::ensure!(!model.trim().is_empty(), "transcription model is required");
    anyhow::ensure!(!bytes.is_empty(), "audio file is empty");
    anyhow::ensure!(bytes.len() <= MAX_TRANSCRIPTION_BYTES, "audio file exceeds 16 MiB");
    let endpoint = transcription_endpoint(provider)?;
    let key = first_api_key(provider)?;
    let file = Part::bytes(bytes)
        .file_name(filename.to_owned())
        .mime_str(content_type)
        .context("invalid audio content type")?;
    let mut form = Form::new()
        .text("model", model.trim().to_owned())
        .part("file", file);
    if let Some(language) = language.map(str::trim).filter(|value| !value.is_empty()) {
        form = form.text("language", language.to_owned());
    }
    let client = reqwest::Client::builder()
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(std::time::Duration::from_secs(45))
        .redirect(reqwest::redirect::Policy::limited(2))
        .build()?;
    let response = client
        .post(endpoint)
        .bearer_auth(key)
        .multipart(form)
        .send()
        .await
        .context("transcription request failed")?;
    let status = response.status();
    let body = response.text().await.context("failed to read transcription response")?;
    if !status.is_success() {
        let detail = body
            .replace(key, "[redacted]")
            .chars()
            .take(300)
            .collect::<String>();
        anyhow::bail!("transcription provider returned HTTP {status}: {detail}");
    }
    let value: serde_json::Value = serde_json::from_str(&body)
        .context("transcription provider returned invalid JSON")?;
    let text = value.get("text").and_then(serde_json::Value::as_str).unwrap_or_default().trim();
    anyhow::ensure!(!text.is_empty(), "transcription provider returned no text");
    Ok(text.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use coomi_engine::ModelCapabilities;
    use std::collections::BTreeMap;

    fn provider(base_url: &str) -> ProviderConfig {
        ProviderConfig {
            id: "p".into(),
            kind: ProviderKind::OpenAiCompatible,
            display: "P".into(),
            api_key: "secret".into(),
            api_keys: Vec::new(),
            base_url: base_url.into(),
            model: "chat".into(),
            fast_model: None,
            models: vec!["chat".into()],
            model_context_windows: BTreeMap::new(),
            model_vision_support: BTreeMap::new(),
            model_parameters: BTreeMap::new(),
            capabilities: ModelCapabilities::default(),
            remote_compaction_mode: Default::default(),
            extra_headers: BTreeMap::new(),
            deepseek_thinking_enabled: true,
            deepseek_search_enabled: false,
        }
    }

    #[test]
    fn normalizes_transcription_endpoint() {
        assert_eq!(transcription_endpoint(&provider("https://api.test/v1")).unwrap(), "https://api.test/v1/audio/transcriptions");
        assert_eq!(transcription_endpoint(&provider("https://api.test")).unwrap(), "https://api.test/v1/audio/transcriptions");
    }
}
