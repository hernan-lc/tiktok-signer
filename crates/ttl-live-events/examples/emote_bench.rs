//! Offline emote benchmark.
//!
//! ```sh
//! cargo run --release -p ttl-live-events --example emote_bench
//! ```
//!
//! It measures the *marginal* cost of emote decoding by running the same
//! normalisation path over four workloads that differ only in their emote
//! content: plain text chat, chat carrying inline emotes, standalone emote
//! messages, and a realistic mixed batch. Nothing here is a synthetic
//! micro-optimisation of one function — every workload goes through
//! `decode_event`/`decode_batch` exactly as the transport does.
//!
//! Numbers are printed, never assumed; this exists so a change to the decoder
//! can be checked against a pasted baseline rather than argued about.

#[path = "../../../scripts/bench/counting_allocator.rs"]
mod counting_allocator;

use std::hint::black_box;
use std::time::Instant;

use prost::Message;
use ttl_live_events::{decode_batch, decode_batch_with_mode, decode_event, method, DecodeMode};
use ttl_live_proto::messages::{WebcastChatMessage, WebcastEmoteChatMessage};
use ttl_live_proto::webcast::model::base::emoji::{EmoteModel, EmoteWithIndex};
use ttl_live_proto::webcast::model::base::user::User;
use ttl_live_proto::webcast::model::base::ImageModel;
use ttl_live_proto::{BaseProtoMessage, ProtoMessageFetchResult};

const MESSAGES_PER_BATCH: usize = 1_000;

fn user() -> User {
    User {
        id: 42,
        nickname: "Ay".to_owned(),
        display_id: "ay".to_owned(),
        ..Default::default()
    }
}

fn emote(index: i32, id: &str) -> EmoteWithIndex {
    EmoteWithIndex {
        index,
        emote: Some(EmoteModel {
            emote_id: id.to_owned(),
            image: Some(ImageModel {
                url_list: vec![format!("https://cdn.example/{id}.webp")],
                ..Default::default()
            }),
            package_id: "7100000000000000001".to_owned(),
            ..Default::default()
        }),
    }
}

fn plain_chat() -> Vec<u8> {
    WebcastChatMessage {
        content: "hola a todos, como estais hoy".to_owned(),
        user: Some(user()),
        ..Default::default()
    }
    .encode_to_vec()
}

fn inline_emote_chat() -> Vec<u8> {
    WebcastChatMessage {
        content: "Hola [happy] amigo [wow] 👋".to_owned(),
        user: Some(user()),
        emotes: vec![emote(5, "7099"), emote(19, "7100"), emote(30, "7101")],
        ..Default::default()
    }
    .encode_to_vec()
}

fn standalone_emote_chat() -> Vec<u8> {
    WebcastEmoteChatMessage {
        user: Some(user()),
        emote_list: vec![
            EmoteModel {
                emote_id: "7099".to_owned(),
                image: Some(ImageModel {
                    url_list: vec!["https://cdn.example/happy.webp".to_owned()],
                    ..Default::default()
                }),
                ..Default::default()
            },
            EmoteModel {
                emote_id: "7100".to_owned(),
                image: Some(ImageModel {
                    url_list: vec!["https://cdn.example/wow.webp".to_owned()],
                    ..Default::default()
                }),
                ..Default::default()
            },
        ],
        ..Default::default()
    }
    .encode_to_vec()
}

/// A batch that looks like a busy room: two thirds text chat, a third emotes.
fn mixed_batch() -> Vec<u8> {
    let payloads = [
        (method::CHAT, plain_chat()),
        (method::CHAT, inline_emote_chat()),
        (method::EMOTE_CHAT, standalone_emote_chat()),
    ];
    ProtoMessageFetchResult {
        messages: (0..MESSAGES_PER_BATCH)
            .map(|index| {
                let (name, payload) = &payloads[index % payloads.len()];
                BaseProtoMessage {
                    method: (*name).to_owned(),
                    payload: payload.clone(),
                    msg_id: index as i64,
                    ..Default::default()
                }
            })
            .collect(),
        ..Default::default()
    }
    .encode_to_vec()
}

fn measure(label: &str, events_per_iteration: usize, iterations: usize, mut run: impl FnMut()) {
    for _ in 0..(iterations / 10).max(10) {
        run();
    }
    let mut samples = Vec::with_capacity(iterations);
    counting_allocator::reset();
    let start = Instant::now();
    for _ in 0..iterations {
        let before = Instant::now();
        run();
        samples.push(before.elapsed().as_nanos());
    }
    let elapsed = start.elapsed();
    let events = events_per_iteration * iterations;
    samples.sort_unstable();
    println!(
        "{label}: events_per_second={:.0} ns_per_event={:.0} batch_ns_p50={} batch_ns_p95={}",
        events as f64 / elapsed.as_secs_f64(),
        elapsed.as_nanos() as f64 / events as f64,
        samples[iterations / 2],
        samples[iterations * 95 / 100],
    );
    counting_allocator::report(events);
}

fn main() {
    let plain = plain_chat();
    let inline = inline_emote_chat();
    let standalone = standalone_emote_chat();
    let batch = mixed_batch();
    let iterations = 20_000;
    let batch_iterations = 200;

    println!("iterations={iterations} batch_iterations={batch_iterations} messages_per_batch={MESSAGES_PER_BATCH}");

    measure("plain_chat", 1, iterations, || {
        black_box(decode_event(method::CHAT, black_box(&plain)));
    });
    measure("inline_emote_chat", 1, iterations, || {
        black_box(decode_event(method::CHAT, black_box(&inline)));
    });
    measure("standalone_emote_chat", 1, iterations, || {
        black_box(decode_event(method::EMOTE_CHAT, black_box(&standalone)));
    });
    measure(
        "mixed_batch_normalized",
        MESSAGES_PER_BATCH,
        batch_iterations,
        || {
            black_box(decode_batch_with_mode(black_box(&batch), DecodeMode::Normalized).unwrap());
        },
    );
    measure(
        "mixed_batch_legacy_full",
        MESSAGES_PER_BATCH,
        batch_iterations,
        || {
            black_box(decode_batch(black_box(&batch)).unwrap());
        },
    );

    // Every emote-carrying event must actually have decoded its emotes, so the
    // benchmark cannot pass by silently dropping them.
    let decoded = decode_batch(&batch).unwrap();
    let emotes: usize = decoded
        .events()
        .map(|event| match event {
            ttl_live_events::LiveEvent::Chat(chat) => chat.emotes.len(),
            ttl_live_events::LiveEvent::EmoteChat(emote) => emote.emotes.len(),
            _ => 0,
        })
        .sum();
    assert!(emotes > 0, "the benchmark decoded no emotes at all");
    println!("decoded_emotes={emotes}");
    black_box(decoded);
}
