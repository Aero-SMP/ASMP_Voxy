//! Thread-local filesystem boundaries: compiled only in the test binary.
use std::{cell::RefCell, path::Path};
type Hook = Box<dyn FnMut(&str, &Path) -> anyhow::Result<()>>;
thread_local! { static HOOK: RefCell<Option<Hook>> = RefCell::new(None); }
pub fn hit(stage: &str, path: &Path) -> anyhow::Result<()> {
    HOOK.with_borrow_mut(|hook| match hook {
        Some(hook) => hook(stage, path),
        None => Ok(()),
    })
}
pub fn set(hook: impl FnMut(&str, &Path) -> anyhow::Result<()> + 'static) -> Guard {
    HOOK.with_borrow_mut(|slot| {
        assert!(slot.is_none());
        *slot = Some(Box::new(hook));
    });
    Guard
}
pub struct Guard;
impl Drop for Guard {
    fn drop(&mut self) {
        HOOK.with_borrow_mut(|slot| *slot = None);
    }
}
