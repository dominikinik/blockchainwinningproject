pub mod create_sla;
pub mod finalize_window;
pub mod initialize_config;
pub mod register_monitor;
pub mod set_monitor_active;
pub mod settle;
pub mod submit_report;

pub use create_sla::*;
pub use finalize_window::*;
pub use initialize_config::*;
pub use register_monitor::*;
pub use set_monitor_active::*;
pub use settle::*;
pub use submit_report::*;

use anchor_lang::prelude::*;

use crate::{constants::MONITOR_SEED, error::SlaError, state::Monitor};

/// Reads the `Monitor` PDA of `authority` from a remaining account. Fails with
/// `MonitorAccountMismatch` unless the account is a `Monitor` owned by this program, at the PDA
/// of `authority`, and (when `writable`) passed writable.
pub(crate) fn read_monitor(info: &AccountInfo, authority: &Pubkey, writable: bool) -> Result<Monitor> {
    require_keys_eq!(*info.owner, crate::ID, SlaError::MonitorAccountMismatch);
    require!(!writable || info.is_writable, SlaError::MonitorAccountMismatch);
    let monitor = Monitor::try_deserialize(&mut &info.try_borrow_data()?[..])
        .map_err(|_| error!(SlaError::MonitorAccountMismatch))?;
    require_keys_eq!(monitor.authority, *authority, SlaError::MonitorAccountMismatch);
    let pda = Pubkey::create_program_address(
        &[MONITOR_SEED, authority.as_ref(), &[monitor.bump]],
        &crate::ID,
    )
    .map_err(|_| error!(SlaError::MonitorAccountMismatch))?;
    require_keys_eq!(pda, *info.key, SlaError::MonitorAccountMismatch);
    Ok(monitor)
}

/// Writes a `Monitor` read by `read_monitor(.., writable = true)` back to its account.
pub(crate) fn write_monitor(info: &AccountInfo, monitor: &Monitor) -> Result<()> {
    let mut data = info.try_borrow_mut_data()?;
    monitor.try_serialize(&mut &mut data[..])
}
