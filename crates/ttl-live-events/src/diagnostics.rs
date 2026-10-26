//! Diagnostics preserve partial events without treating absent proto3 values as proof of completeness.
use crate::{DecodedEvent, SchemaField, SchemaValue};
use serde::Serialize;
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum NormalizationStatus {
    Normalized,
    UnsupportedMethod,
    MalformedWire,
    DecodeError,
    PayloadLimit,
    FieldLimit,
    BatchFieldLimit,
    DepthLimit,
    NotRequested,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct UnknownField {
    /// Numeric path remains stable when descriptor names change.
    pub path: Vec<u32>,
    pub wire_type: u8,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct UnknownEnum {
    pub path: Vec<u32>,
    pub enum_type: &'static str,
    pub number: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct EventDiagnostics {
    pub normalization: NormalizationStatus,
    pub schema_known: bool,
    pub schema_truncated: bool,
    pub schema_warning: bool,
    pub unknown_fields: Vec<UnknownField>,
    pub unknown_enums: Vec<UnknownEnum>,
    /// Presence observations, not validity requirements. Partial gift repeats are legitimate.
    /// Only inspected top-level fields are reported; a truncated view can hide later fields.
    pub absent_fields: Vec<&'static str>,
    pub presence_complete: bool,
}

/// Bounded, content-free telemetry for captures and long-running consumers.
#[derive(Debug, Default, Serialize)]
pub struct AuditReport {
    pub batches: u64,
    pub events: u64,
    pub untracked_methods: u64,
    pub methods: BTreeMap<String, MethodAudit>,
}

#[derive(Debug, Default, Serialize)]
pub struct MethodAudit {
    pub events: u64,
    /// Counts usable normalized emotes, including those arriving after raw capture retention ends.
    pub emotes: u64,
    pub events_with_emotes: u64,
    pub wire_emote_entries: u64,
    pub incomplete_wire_emote_presence: u64,
    pub normalization: BTreeMap<NormalizationStatus, u64>,
    pub unknown_schema: u64,
    pub truncated_schema: u64,
    pub warnings: u64,
    pub incomplete_presence: u64,
    pub absent_fields: BTreeMap<String, u64>,
    /// Keys are numeric path + wire type; no payload content or participant IDs.
    pub unknown_fields: BTreeMap<String, u64>,
    pub untracked_fields: u64,
    pub unknown_enums: BTreeMap<String, u64>,
    pub untracked_enums: u64,
    pub unclassified_social_actions: BTreeMap<i64, u64>,
    pub untracked_social_actions: u64,
}

impl AuditReport {
    pub fn observe(&mut self, batch: &crate::EventBatch) {
        self.batches += 1;
        self.events += batch.events.len() as u64;
        for event in &batch.events {
            // Do not let future methods or gigantic method names grow telemetry without bound.
            if event.method().len() > 256
                || (!self.methods.contains_key(event.method()) && self.methods.len() >= 256)
            {
                self.untracked_methods += 1;
                continue;
            }
            let method = self.methods.entry(event.method().to_owned()).or_default();
            let diagnostics = event.diagnostics();
            method.events += 1;
            let emotes = match &event.event {
                crate::LiveEvent::Chat(chat) => chat.emotes.len(),
                crate::LiveEvent::EmoteChat(chat) => chat.emotes.len(),
                _ => 0,
            };
            method.emotes += emotes as u64;
            method.events_with_emotes += u64::from(emotes > 0);
            if let Some((entries, complete)) = event.wire_emote_entries() {
                method.wire_emote_entries += entries as u64;
                method.incomplete_wire_emote_presence += u64::from(!complete);
            }
            *method
                .normalization
                .entry(diagnostics.normalization)
                .or_default() += 1;
            method.unknown_schema += u64::from(!diagnostics.schema_known);
            method.truncated_schema += u64::from(diagnostics.schema_truncated);
            method.warnings += u64::from(diagnostics.schema_warning);
            method.incomplete_presence += u64::from(!diagnostics.presence_complete);
            if let crate::LiveEvent::Social(social) = &event.event {
                if matches!(
                    social.classify().kind,
                    crate::SocialKind::Other | crate::SocialKind::Unknown
                ) {
                    if method
                        .unclassified_social_actions
                        .contains_key(&social.action)
                        || method.unclassified_social_actions.len() < 128
                    {
                        *method
                            .unclassified_social_actions
                            .entry(social.action)
                            .or_default() += 1;
                    } else {
                        method.untracked_social_actions += 1;
                    }
                }
            }
            let enums: std::collections::BTreeSet<_> = diagnostics
                .unknown_enums
                .iter()
                .map(|field| {
                    format!(
                        "{}:{}={}",
                        field
                            .path
                            .iter()
                            .map(u32::to_string)
                            .collect::<Vec<_>>()
                            .join("."),
                        field.enum_type,
                        field.number
                    )
                })
                .collect();
            for key in enums {
                if method.unknown_enums.contains_key(&key) || method.unknown_enums.len() < 1024 {
                    *method.unknown_enums.entry(key).or_default() += 1;
                } else {
                    method.untracked_enums += 1;
                }
            }
            for field in diagnostics.absent_fields {
                *method.absent_fields.entry(field.into()).or_default() += 1;
            }
            // One occurrence per event avoids repeated wire fields skewing prevalence.
            let fields: std::collections::BTreeSet<_> = diagnostics
                .unknown_fields
                .iter()
                .map(|field| {
                    format!(
                        "{}:wire{}",
                        field
                            .path
                            .iter()
                            .map(u32::to_string)
                            .collect::<Vec<_>>()
                            .join("."),
                        field.wire_type
                    )
                })
                .collect();
            for field in fields {
                if !method.unknown_fields.contains_key(&field)
                    && method.unknown_fields.len() >= 1024
                {
                    method.untracked_fields += 1;
                } else {
                    *method.unknown_fields.entry(field).or_default() += 1;
                }
            }
        }
    }
}

impl DecodedEvent {
    /// Count the pinned emote field on the original wire, independently of normalization
    /// and diagnostic depth. `complete` refers to top-level wire inspection, not semantics.
    pub fn wire_emote_entries(&self) -> Option<(usize, bool)> {
        use ttl_sign_core::proto::{Reader, WireValue};
        let name = match self.method() {
            crate::method::CHAT => "emotes",
            crate::method::EMOTE_CHAT => "emote_list",
            _ => return None,
        };
        let target = self
            .schema
            .schema?
            .fields
            .iter()
            .find(|field| field.name == name)?
            .number;
        let payload_complete = self.normalization_status != NormalizationStatus::PayloadLimit;
        let mut reader = Reader::new(self.raw_payload());
        let mut entries = 0;
        for _ in 0..4096 {
            match reader.next_field() {
                None => return Some((entries, payload_complete)),
                Some(Ok((number, WireValue::Bytes(_)))) if number == target => entries += 1,
                Some(Ok(_)) => (),
                Some(Err(_)) => return Some((entries, false)),
            }
        }
        Some((entries, payload_complete && reader.next_field().is_none()))
    }

    pub fn diagnostics(&self) -> EventDiagnostics {
        fn visit(
            fields: &[SchemaField],
            path: &mut Vec<u32>,
            output: &mut Vec<UnknownField>,
            enums: &mut Vec<UnknownEnum>,
        ) {
            for field in fields {
                path.push(field.number);
                if field.descriptor.is_none() {
                    output.push(UnknownField {
                        path: path.clone(),
                        wire_type: field.wire_type,
                    });
                }
                if let SchemaValue::Message(object) = &field.value {
                    visit(&object.fields, path, output, enums);
                }
                if let SchemaValue::Enum {
                    number,
                    enum_type,
                    name: None,
                } = &field.value
                {
                    enums.push(UnknownEnum {
                        path: path.clone(),
                        enum_type,
                        number: *number,
                    });
                }
                path.pop();
            }
        }
        let mut unknown_fields = Vec::new();
        let mut unknown_enums = Vec::new();
        visit(
            &self.schema.fields,
            &mut Vec::new(),
            &mut unknown_fields,
            &mut unknown_enums,
        );
        let observed: &[&str] = match self.method() {
            crate::method::CHAT => &["user", "content"],
            crate::method::EMOTE_CHAT => &["user", "emote_list"],
            crate::method::GIFT => &["user", "gift_id", "gift", "repeat_count"],
            crate::method::LIKE => &["user", "count", "total"],
            crate::method::MEMBER => &["user"],
            crate::method::SOCIAL => &["user", "action"],
            _ => &[],
        };
        // A truncated schema cannot prove absence: report no speculative missing fields.
        let presence_complete = !self.schema.truncated && !self.schema.warning;
        let absent_fields = if presence_complete {
            observed
                .iter()
                .filter_map(|name| self.schema.field_named(name).is_none().then_some(*name))
                .collect()
        } else {
            Vec::new()
        };
        EventDiagnostics {
            normalization: self.normalization_status,
            schema_known: self.schema.is_known(),
            schema_truncated: self.schema.truncated,
            schema_warning: self.schema.warning,
            unknown_fields,
            unknown_enums,
            absent_fields,
            presence_complete,
        }
    }
}
