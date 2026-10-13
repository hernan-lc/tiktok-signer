//! Lifetime leases: bounded global sockets and bounded active customer accounting.
use crate::{error::ApiError, metrics::Metrics};
use std::collections::HashMap;
use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

pub(crate) struct RelayLimits {
    slots: Arc<Semaphore>,
    customers: Mutex<HashMap<String, usize>>,
    per_customer: usize,
    metrics: Arc<Metrics>,
}
pub(crate) struct RelayPermit {
    limits: Arc<RelayLimits>,
    customer: String,
    _slot: OwnedSemaphorePermit,
}
impl RelayLimits {
    pub(crate) fn new(global: usize, per_customer: usize, metrics: Arc<Metrics>) -> Arc<Self> {
        Arc::new(Self {
            slots: Arc::new(Semaphore::new(global.max(1))),
            customers: Mutex::new(HashMap::new()),
            per_customer: per_customer.max(1),
            metrics,
        })
    }
    pub(crate) fn acquire(self: &Arc<Self>, customer: &str) -> Result<RelayPermit, ApiError> {
        let denied = || {
            self.metrics
                .live_connection_rejections_total
                .fetch_add(1, Ordering::Relaxed);
            ApiError::RateLimited {
                message: "active LIVE connection limit reached".into(),
                retry_after_ms: 1000,
            }
        };
        let slot = self
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| denied())?;
        let mut customers = self.customers.lock().unwrap_or_else(|e| e.into_inner());
        let count = customers.entry(customer.to_owned()).or_default();
        if *count >= self.per_customer {
            return Err(denied());
        }
        *count += 1;
        self.metrics
            .active_live_connections
            .fetch_add(1, Ordering::Relaxed);
        Ok(RelayPermit {
            limits: self.clone(),
            customer: customer.to_owned(),
            _slot: slot,
        })
    }
}
impl Drop for RelayPermit {
    fn drop(&mut self) {
        let mut customers = self
            .limits
            .customers
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(count) = customers.get_mut(&self.customer) {
            *count -= 1;
            if *count == 0 {
                customers.remove(&self.customer);
            }
        }
        self.limits
            .metrics
            .active_live_connections
            .fetch_sub(1, Ordering::Relaxed);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn global_and_customer_leases_release_on_every_drop() {
        let metrics = Arc::new(Metrics::default());
        let limits = RelayLimits::new(2, 1, metrics.clone());
        let a = limits.acquire("a").unwrap();
        assert!(limits.acquire("a").is_err());
        let b = limits.acquire("b").unwrap();
        assert!(limits.acquire("c").is_err());
        assert_eq!(metrics.active_live_connections.load(Ordering::Relaxed), 2);
        drop(a);
        drop(b);
        for i in 0..1000 {
            drop(limits.acquire(&i.to_string()).unwrap());
        }
        assert!(limits.customers.lock().unwrap().is_empty());
        assert_eq!(metrics.active_live_connections.load(Ordering::Relaxed), 0);
        assert_eq!(
            metrics
                .live_connection_rejections_total
                .load(Ordering::Relaxed),
            2
        );
    }
}
