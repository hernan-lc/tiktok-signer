//! Benchmark-only cumulative allocation instrumentation; never linked into SDK libraries.
use std::alloc::{GlobalAlloc, Layout, System};
use std::sync::atomic::{AtomicU64, Ordering};
struct Counting;
static CALLS: AtomicU64 = AtomicU64::new(0);
static BYTES: AtomicU64 = AtomicU64::new(0);
#[cfg(target_os = "linux")]
static CPU_TICKS: AtomicU64 = AtomicU64::new(0);
#[cfg(target_os = "linux")]
fn cpu_ticks() -> Option<u64> {
    let stat = std::fs::read_to_string("/proc/self/stat").ok()?;
    let fields: Vec<_> = stat.rsplit_once(')')?.1.split_whitespace().collect();
    Some(fields.get(11)?.parse::<u64>().ok()? + fields.get(12)?.parse::<u64>().ok()?)
}
#[global_allocator]
static ALLOCATOR: Counting = Counting;
// SAFETY: layouts, pointers, and allocation semantics are forwarded unchanged to System.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        CALLS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        // SAFETY: the caller provides a valid allocation layout.
        unsafe { System.alloc(layout) }
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        CALLS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(layout.size() as u64, Ordering::Relaxed);
        // SAFETY: the caller provides a valid allocation layout.
        unsafe { System.alloc_zeroed(layout) }
    }
    unsafe fn realloc(&self, ptr: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        CALLS.fetch_add(1, Ordering::Relaxed);
        BYTES.fetch_add(size as u64, Ordering::Relaxed);
        // SAFETY: the caller provides the original allocation and valid new size.
        unsafe { System.realloc(ptr, layout, size) }
    }
    unsafe fn dealloc(&self, ptr: *mut u8, layout: Layout) {
        // SAFETY: ownership and original layout are supplied by the caller.
        unsafe { System.dealloc(ptr, layout) }
    }
}
pub fn reset() {
    #[cfg(target_os = "linux")]
    CPU_TICKS.store(cpu_ticks().unwrap_or(0), Ordering::Relaxed);
    CALLS.store(0, Ordering::Relaxed);
    BYTES.store(0, Ordering::Relaxed);
}
pub fn report(events: usize) {
    let calls = CALLS.load(Ordering::Relaxed);
    let bytes = BYTES.load(Ordering::Relaxed);
    println!(
        "allocation_calls_per_event={:.2} requested_bytes_per_event={:.2}",
        calls as f64 / events as f64,
        bytes as f64 / events as f64
    );
    #[cfg(target_os = "linux")]
    if let Some(ticks) = cpu_ticks() {
        if let Ok(output) = std::process::Command::new("getconf")
            .arg("CLK_TCK")
            .output()
        {
            if let Ok(rate) = String::from_utf8_lossy(&output.stdout)
                .trim()
                .parse::<f64>()
            {
                if rate > 0.0 {
                    let used = ticks.saturating_sub(CPU_TICKS.load(Ordering::Relaxed));
                    println!(
                        "process_cpu_ns_per_event={:.2} cpu_tick_resolution_ns={:.0}",
                        used as f64 * 1e9 / rate / events as f64,
                        1e9 / rate
                    );
                }
            }
        }
    }
    if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
        for line in status.lines().filter(|s| {
            s.starts_with("VmRSS:") || s.starts_with("VmHWM:") || s.starts_with("Threads:")
        }) {
            println!("{line}");
        }
    }
}
