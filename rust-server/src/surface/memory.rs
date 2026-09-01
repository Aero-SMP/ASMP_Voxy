//! One process-wide, byte-accounted memory budget.
//!
//! The budget is deliberately global: category counters explain ownership, but no subsystem can
//! consume an independent allowance that multiplies with dimensions or connections. Ordinary
//! work cannot consume the protected control reserve, so shutdown, error reporting, and small
//! root-publication records remain possible under pressure.

use anyhow::{Result, bail};
use std::{
    fmt,
    sync::{Arc, Condvar, Mutex},
};
use tokio::sync::Notify;

pub const DEFAULT_MANAGED_MEMORY_MIB: usize = 2 * 1024;
pub const MIN_MANAGED_MEMORY_MIB: usize = 256;
pub const MAX_MANAGED_MEMORY_MIB: usize = 64 * 1024;
const CONTROL_RESERVE_MIB: usize = 32;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(usize)]
pub enum MemoryClass {
    Build,
    Visibility,
    Network,
    PackIndex,
    ObjectIo,
    Cache,
    Maintenance,
    Control,
}

impl MemoryClass {
    const COUNT: usize = Self::Control as usize + 1;

    fn priority(self) -> MemoryPriority {
        match self {
            Self::Control => MemoryPriority::Control,
            Self::Network | Self::Cache => MemoryPriority::Interactive,
            Self::Build | Self::Visibility | Self::PackIndex | Self::ObjectIo => {
                MemoryPriority::Publication
            }
            Self::Maintenance => MemoryPriority::Maintenance,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
#[repr(usize)]
pub enum MemoryPriority {
    Control,
    Interactive,
    Publication,
    Maintenance,
}

impl MemoryPriority {
    const COUNT: usize = Self::Maintenance as usize + 1;
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MemorySnapshot {
    pub limit: usize,
    pub ordinary_limit: usize,
    pub used: usize,
    pub peak: usize,
    pub by_class: [usize; MemoryClass::COUNT],
}

#[derive(Debug)]
struct State {
    used: usize,
    peak: usize,
    by_class: [usize; MemoryClass::COUNT],
    waiters: [usize; MemoryPriority::COUNT],
    waiter_bytes: [usize; MemoryPriority::COUNT],
}

/// A typed error allows callers to defer work without treating pressure as corruption.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MemoryPressure {
    pub requested: usize,
    pub available: usize,
    pub class: MemoryClass,
}

impl fmt::Display for MemoryPressure {
    fn fmt(&self, output: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            output,
            "global Voxy memory pressure: {:?} requested {} bytes, {} available",
            self.class, self.requested, self.available
        )
    }
}

impl std::error::Error for MemoryPressure {}

#[derive(Debug)]
pub struct ServerMemoryBudget {
    limit: usize,
    ordinary_limit: usize,
    state: Mutex<State>,
    released: Condvar,
    async_released: Notify,
}

impl ServerMemoryBudget {
    pub fn from_mib(mib: usize) -> Result<Arc<Self>> {
        if !(MIN_MANAGED_MEMORY_MIB..=MAX_MANAGED_MEMORY_MIB).contains(&mib) {
            bail!(
                "memory.managed_mib must be between {MIN_MANAGED_MEMORY_MIB} and {MAX_MANAGED_MEMORY_MIB}"
            );
        }
        let limit = mib
            .checked_mul(1024 * 1024)
            .ok_or_else(|| anyhow::anyhow!("managed memory limit overflow"))?;
        let reserve = (CONTROL_RESERVE_MIB * 1024 * 1024).min(limit / 4);
        Ok(Arc::new(Self {
            limit,
            ordinary_limit: limit - reserve,
            state: Mutex::new(State {
                used: 0,
                peak: 0,
                by_class: [0; MemoryClass::COUNT],
                waiters: [0; MemoryPriority::COUNT],
                waiter_bytes: [0; MemoryPriority::COUNT],
            }),
            released: Condvar::new(),
            async_released: Notify::new(),
        }))
    }

    pub fn default_budget() -> Arc<Self> {
        Self::from_mib(DEFAULT_MANAGED_MEMORY_MIB).expect("default memory budget is valid")
    }

    pub fn limit(&self) -> usize {
        self.limit
    }

    /// Upper bound for a temporary reservation that accounts for a decoded durable state graph.
    /// Retained state itself grows against the global budget rather than an independent
    /// per-dimension quota. One eighth remains available for indexes and interactive work.
    pub fn bulk_state_reservation_limit(&self) -> usize {
        self.ordinary_limit / 8 * 7
    }

    pub fn snapshot(&self) -> MemorySnapshot {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        MemorySnapshot {
            limit: self.limit,
            ordinary_limit: self.ordinary_limit,
            used: state.used,
            peak: state.peak,
            by_class: state.by_class,
        }
    }

    pub fn try_reserve(
        self: &Arc<Self>,
        class: MemoryClass,
        bytes: usize,
    ) -> std::result::Result<MemoryPermit, MemoryPressure> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        self.reserve_locked(&mut state, class, bytes, false)?;
        Ok(MemoryPermit {
            budget: self.clone(),
            class,
            bytes,
        })
    }

    /// Waits without holding any other resource. Callers must acquire the complete reservation
    /// before opening input or retaining partial output.
    pub fn reserve_blocking(
        self: &Arc<Self>,
        class: MemoryClass,
        bytes: usize,
    ) -> std::result::Result<MemoryPermit, MemoryPressure> {
        self.reject_impossible(class, bytes)?;
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        let priority = class.priority();
        state.waiters[priority as usize] += 1;
        state.waiter_bytes[priority as usize] =
            state.waiter_bytes[priority as usize].saturating_add(bytes);
        loop {
            match self.reserve_locked(&mut state, class, bytes, true) {
                Ok(()) => {
                    state.waiters[priority as usize] -= 1;
                    state.waiter_bytes[priority as usize] = state.waiter_bytes[priority as usize]
                        .checked_sub(bytes)
                        .expect("memory waiter-byte accounting underflow");
                    let permit = MemoryPermit {
                        budget: self.clone(),
                        class,
                        bytes,
                    };
                    // Removing a high-priority waiter may make an independent smaller
                    // lower-priority request admissible even while this permit remains held.
                    drop(state);
                    self.released.notify_all();
                    self.async_released.notify_waiters();
                    return Ok(permit);
                }
                Err(_) => {
                    state = self
                        .released
                        .wait(state)
                        .unwrap_or_else(|poison| poison.into_inner());
                }
            }
        }
    }

    pub async fn reserve(
        self: &Arc<Self>,
        class: MemoryClass,
        bytes: usize,
    ) -> std::result::Result<MemoryPermit, MemoryPressure> {
        self.reject_impossible(class, bytes)?;
        let priority = class.priority();
        {
            let mut state = self
                .state
                .lock()
                .unwrap_or_else(|poison| poison.into_inner());
            state.waiters[priority as usize] += 1;
            state.waiter_bytes[priority as usize] =
                state.waiter_bytes[priority as usize].saturating_add(bytes);
        }
        let mut registration = AsyncWaiter {
            budget: self.clone(),
            priority,
            bytes,
            registered: true,
        };
        loop {
            // `notify_waiters` does not retain a permit. Enable the waiter before the
            // availability check so a release between the check and `.await` cannot be lost.
            let notified = self.async_released.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            let acquired = {
                let mut state = self
                    .state
                    .lock()
                    .unwrap_or_else(|poison| poison.into_inner());
                self.reserve_locked(&mut state, class, bytes, true).is_ok()
            };
            if acquired {
                registration.unregister();
                return Ok(MemoryPermit {
                    budget: self.clone(),
                    class,
                    bytes,
                });
            }
            notified.as_mut().await;
        }
    }

    fn reserve_locked(
        &self,
        state: &mut State,
        class: MemoryClass,
        bytes: usize,
        _registered: bool,
    ) -> std::result::Result<(), MemoryPressure> {
        let ceiling = if class == MemoryClass::Control {
            self.limit
        } else {
            self.ordinary_limit
        };
        let available = ceiling.saturating_sub(state.used);
        let priority = class.priority() as usize;
        // Preserve enough room for the concrete requests of higher-priority waiters without
        // turning their mere presence into an absolute publication barrier. The old boolean
        // gate could indefinitely reject an 8 MiB object write while more than 8 GiB was free
        // whenever a tiny network read happened to register first.
        let protected = state.waiter_bytes[..priority]
            .iter()
            .copied()
            .fold(0usize, usize::saturating_add);
        let admissible = available.saturating_sub(protected);
        if bytes > admissible {
            return Err(MemoryPressure {
                requested: bytes,
                available: admissible,
                class,
            });
        }
        state.used += bytes;
        state.peak = state.peak.max(state.used);
        state.by_class[class as usize] += bytes;
        Ok(())
    }

    fn reject_impossible(
        &self,
        class: MemoryClass,
        bytes: usize,
    ) -> std::result::Result<(), MemoryPressure> {
        let ceiling = if class == MemoryClass::Control {
            self.limit
        } else {
            self.ordinary_limit
        };
        if bytes > ceiling {
            return Err(MemoryPressure {
                requested: bytes,
                available: ceiling,
                class,
            });
        }
        Ok(())
    }

    fn release(&self, class: MemoryClass, bytes: usize) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        state.used = state
            .used
            .checked_sub(bytes)
            .expect("memory permit accounting underflow");
        state.by_class[class as usize] = state.by_class[class as usize]
            .checked_sub(bytes)
            .expect("memory class accounting underflow");
        drop(state);
        self.released.notify_all();
        self.async_released.notify_waiters();
    }
}

struct AsyncWaiter {
    budget: Arc<ServerMemoryBudget>,
    priority: MemoryPriority,
    bytes: usize,
    registered: bool,
}

impl AsyncWaiter {
    fn unregister(&mut self) {
        if !self.registered {
            return;
        }
        let mut state = self
            .budget
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        state.waiters[self.priority as usize] = state.waiters[self.priority as usize]
            .checked_sub(1)
            .expect("memory waiter accounting underflow");
        state.waiter_bytes[self.priority as usize] = state.waiter_bytes[self.priority as usize]
            .checked_sub(self.bytes)
            .expect("memory waiter-byte accounting underflow");
        self.registered = false;
    }
}

impl Drop for AsyncWaiter {
    fn drop(&mut self) {
        self.unregister();
        self.budget.released.notify_all();
        self.budget.async_released.notify_waiters();
    }
}

#[derive(Debug)]
pub struct MemoryPermit {
    budget: Arc<ServerMemoryBudget>,
    class: MemoryClass,
    bytes: usize,
}

impl MemoryPermit {
    pub fn bytes(&self) -> usize {
        self.bytes
    }

    pub fn class(&self) -> MemoryClass {
        self.class
    }

    pub(crate) fn accounts_for(&self, budget: &Arc<ServerMemoryBudget>, bytes: usize) -> bool {
        Arc::ptr_eq(&self.budget, budget) && self.bytes >= bytes
    }

    pub fn shrink_to(&mut self, bytes: usize) {
        assert!(
            bytes <= self.bytes,
            "memory permit cannot grow through shrink_to"
        );
        let released = self.bytes - bytes;
        self.bytes = bytes;
        if released != 0 {
            self.budget.release(self.class, released);
        }
    }

    pub fn try_grow(&mut self, additional: usize) -> std::result::Result<(), MemoryPressure> {
        let mut state = self
            .budget
            .state
            .lock()
            .unwrap_or_else(|poison| poison.into_inner());
        self.budget
            .reserve_locked(&mut state, self.class, additional, false)?;
        self.bytes = self
            .bytes
            .checked_add(additional)
            .expect("memory permit size overflow");
        Ok(())
    }
}

impl Drop for MemoryPermit {
    fn drop(&mut self) {
        self.budget.release(self.class, self.bytes);
    }
}
