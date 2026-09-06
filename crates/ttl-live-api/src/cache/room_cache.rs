use std::collections::HashMap;
use std::time::{Duration, Instant};

use tokio::sync::Mutex;

use crate::service::RoomResolution;

#[derive(Debug)]
struct Entry {
    value: RoomResolution,
    expires_at: Instant,
}

/// A short-lived, bounded cache for `uniqueId -> room` results.
///
/// Signed URLs are intentionally not cached here. A room hit only avoids the relatively expensive
/// TikTok lookup; every live API request still asks the warm signer for a fresh URL.
#[derive(Debug)]
pub struct RoomCache {
    entries: Mutex<HashMap<String, Entry>>,
    live_ttl: Duration,
    offline_ttl: Duration,
    not_found_ttl: Duration,
    capacity: usize,
}

impl RoomCache {
    pub fn new(
        live_ttl: Duration,
        offline_ttl: Duration,
        not_found_ttl: Duration,
        capacity: usize,
    ) -> Self {
        Self {
            entries: Mutex::new(HashMap::new()),
            live_ttl,
            offline_ttl,
            not_found_ttl,
            capacity: capacity.max(1),
        }
    }

    pub async fn get(&self, unique_id: &str) -> Option<RoomResolution> {
        let mut entries = self.entries.lock().await;
        let entry = entries.get(unique_id)?;
        if entry.expires_at <= Instant::now() {
            entries.remove(unique_id);
            return None;
        }
        Some(entry.value.clone())
    }

    pub async fn put(&self, unique_id: String, value: RoomResolution) {
        let ttl = match &value {
            RoomResolution::Live { .. } => self.live_ttl,
            RoomResolution::Offline { .. } => self.offline_ttl,
            RoomResolution::NotFound => self.not_found_ttl,
        };
        let mut entries = self.entries.lock().await;
        prune_expired(&mut entries);
        if entries.len() >= self.capacity && !entries.contains_key(&unique_id) {
            // The cache is an optimization, not a correctness boundary. Evicting one arbitrary
            // live entry keeps memory bounded without an O(n) LRU bookkeeping cost.
            if let Some(key) = entries.keys().next().cloned() {
                entries.remove(&key);
            }
        }
        entries.insert(
            unique_id,
            Entry {
                value,
                expires_at: Instant::now() + ttl,
            },
        );
    }

    pub async fn invalidate(&self, unique_id: &str) {
        self.entries.lock().await.remove(unique_id);
    }

    pub async fn len(&self) -> usize {
        let mut entries = self.entries.lock().await;
        prune_expired(&mut entries);
        entries.len()
    }

    pub async fn is_empty(&self) -> bool {
        self.len().await == 0
    }
}

fn prune_expired(entries: &mut HashMap<String, Entry>) {
    let now = Instant::now();
    entries.retain(|_, entry| entry.expires_at > now);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn entries_expire_and_invalidation_is_explicit() {
        let cache = RoomCache::new(
            Duration::from_millis(20),
            Duration::from_millis(20),
            Duration::from_millis(20),
            2,
        );
        cache
            .put(
                "creator".into(),
                RoomResolution::Live {
                    room_id: "1".into(),
                    status: 2,
                    nickname: "Creator".into(),
                },
            )
            .await;
        assert!(cache.get("creator").await.is_some());
        cache.invalidate("creator").await;
        assert!(cache.get("creator").await.is_none());
    }

    #[tokio::test]
    async fn capacity_is_bounded() {
        let cache = RoomCache::new(
            Duration::from_secs(30),
            Duration::from_secs(30),
            Duration::from_secs(30),
            2,
        );
        for id in ["one", "two", "three"] {
            cache.put(id.into(), RoomResolution::NotFound).await;
        }
        assert_eq!(cache.len().await, 2);
    }
}
