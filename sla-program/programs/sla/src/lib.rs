pub mod constants;
pub mod error;
pub mod events;
pub mod instructions;
pub mod logic;
pub mod state;

use anchor_lang::prelude::*;

pub use constants::*;
pub use events::*;
pub use instructions::*;
pub use state::*;

declare_id!("4ACuzhWwVVqtbYgicWzq11BtowhsEEHLcChJn2gVR4R9");

/// Interface frozen in Phase 0; see SPEC.md. Handlers are stubs until T1.
#[program]
pub mod sla {
    use super::*;

    pub fn initialize_config(ctx: Context<InitializeConfig>, params: ConfigParams) -> Result<()> {
        crate::instructions::initialize_config::handle_initialize_config(ctx, params)
    }

    pub fn register_monitor(ctx: Context<RegisterMonitor>, name: String) -> Result<()> {
        crate::instructions::register_monitor::handle_register_monitor(ctx, name)
    }

    pub fn set_monitor_active(ctx: Context<SetMonitorActive>, active: bool) -> Result<()> {
        crate::instructions::set_monitor_active::handle_set_monitor_active(ctx, active)
    }

    pub fn create_sla(
        ctx: Context<CreateSla>,
        sla_id: [u8; 16],
        params: CreateSlaParams,
        monitors: Vec<Pubkey>,
    ) -> Result<()> {
        crate::instructions::create_sla::handle_create_sla(ctx, sla_id, params, monitors)
    }

    pub fn submit_report(
        ctx: Context<SubmitReport>,
        window_index: u32,
        checked: [u8; 32],
        up: [u8; 32],
    ) -> Result<()> {
        crate::instructions::submit_report::handle_submit_report(ctx, window_index, checked, up)
    }

    pub fn finalize_window(ctx: Context<FinalizeWindow>, window_index: u32) -> Result<()> {
        crate::instructions::finalize_window::handle_finalize_window(ctx, window_index)
    }

    pub fn settle(ctx: Context<Settle>) -> Result<()> {
        crate::instructions::settle::handle_settle(ctx)
    }
}
