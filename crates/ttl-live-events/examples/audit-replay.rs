//! Replay private decompressed batches and verify representation parity without printing content.
use std::{collections::BTreeMap, path::PathBuf};
use ttl_live_events::{decode_batch, decode_batch_with_mode, AuditReport, DecodeMode};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let dir = PathBuf::from(
        std::env::args()
            .nth(1)
            .ok_or("usage: audit-replay CAPTURE_DIRECTORY")?,
    );
    let mut paths = std::fs::read_dir(dir)?
        .map(|entry| entry.map(|entry| entry.path()))
        .collect::<Result<Vec<_>, _>>()?;
    paths.retain(|path| {
        path.file_name()
            .is_some_and(|name| name.to_string_lossy().starts_with("batch-"))
            && path.extension().is_some_and(|ext| ext == "pb")
    });
    paths.sort();
    if paths.is_empty() {
        return Err("no captured batches".into());
    }
    let mut report = AuditReport::default();
    // Optional private JSONL for independent SDK comparison; never print event content.
    let mut event_output = if let Some(path) = std::env::args().nth(2) {
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        Some(std::io::BufWriter::new(options.open(path)?))
    } else {
        None
    };
    let mut errors = BTreeMap::new();
    for path in paths {
        if path.metadata()?.len() > 16 * 1024 * 1024 {
            return Err("oversized captured batch".into());
        }
        let bytes = std::fs::read(&path)?;
        let batch = match decode_batch(&bytes) {
            Ok(batch) => batch,
            Err(error) => {
                errors.insert(
                    path.file_name().unwrap().to_string_lossy().into_owned(),
                    error.to_string(),
                );
                continue;
            }
        };
        report.observe(&batch);
        if let Some(output) = &mut event_output {
            use std::io::Write;
            for (index, event) in batch.events.iter().enumerate() {
                let normalized = event
                    .to_json(ttl_live_events::JsonMode::Normalized)
                    .map(|json| serde_json::from_str::<serde_json::Value>(&json).unwrap());
                serde_json::to_writer(
                    &mut *output,
                    &serde_json::json!({
                        "batch": path.file_name().unwrap().to_string_lossy(), "index": index,
                        "method": event.method(), "message_id": event.message_id().to_string(),
                        "history": event.raw.is_history, "normalized": normalized,
                    }),
                )?;
                writeln!(output)?;
            }
        }
        for mode in [
            DecodeMode::Full,
            DecodeMode::Normalized,
            DecodeMode::Schema,
            DecodeMode::Raw,
        ] {
            let selected = decode_batch_with_mode(&bytes, mode)?;
            assert_eq!(
                selected.events.len(),
                batch.events.len(),
                "event count: {}",
                path.display()
            );
            assert_eq!(selected.cursor, batch.cursor);
            assert_eq!(selected.internal_ext, batch.internal_ext);
            assert_eq!(selected.need_ack, batch.need_ack);
            for (event, original) in selected.events.iter().zip(&batch.events) {
                assert_eq!(event.method, original.raw.method);
                assert_eq!(event.msg_id, original.raw.msg_id);
                assert_eq!(event.is_history, original.raw.is_history);
                assert_eq!(event.timestamp, original.timestamp());
                if matches!(mode, DecodeMode::Full | DecodeMode::Normalized) {
                    assert_eq!(
                        event.event.as_ref(),
                        Some(&original.event),
                        "canonical parity: {}",
                        path.display()
                    );
                    assert_eq!(event.normalization_status, original.normalization_status);
                }
                if let Some(payload) = &event.payload {
                    assert_eq!(payload, &original.raw.payload);
                }
                if let Some(schema) = &event.schema {
                    assert_eq!(schema, &original.schema);
                }
            }
        }
    }
    println!(
        "{}",
        serde_json::to_string_pretty(&serde_json::json!({
            "upstream_commit": ttl_live_proto::UPSTREAM_COMMIT, "audit": report,
            "batch_errors": errors, "representation_parity": "passed"
        }))?
    );
    if !errors.is_empty() {
        return Err("some captured batches failed decoding; see batch_errors".into());
    }
    Ok(())
}
