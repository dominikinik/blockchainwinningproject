use anchor_lang::prelude::*;

use crate::{constants::*, state::Sla};

/// Remaining accounts: the `Monitor` PDA of every entry in `sla.monitors`, in the same order
/// (writable), for agreement stats.
#[derive(Accounts)]
#[instruction(window_index: u32)]
pub struct FinalizeWindow<'info> {
    #[account(
        mut,
        seeds = [SLA_SEED, sla.customer.as_ref(), sla.sla_id.as_ref()],
        bump = sla.bump
    )]
    pub sla: Account<'info, Sla>,
    /// CHECK: the window's `WindowReport` PDA, always passed so a caller cannot hide existing
    /// reports. Either uninitialized (no monitor reported: the window counts 0 checks) or a
    /// `WindowReport` owned by this program, which the handler reads and closes to `payer`.
    #[account(
        mut,
        seeds = [WINDOW_SEED, sla.key().as_ref(), window_index.to_le_bytes().as_ref()],
        bump
    )]
    pub window_report: UncheckedAccount<'info>,
    /// CHECK: receives the closed `WindowReport` rent. Must equal `WindowReport::payer` when the
    /// report exists (`PayerMismatch`); otherwise any writable account (e.g. the caller).
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
}

pub fn handle_finalize_window(_ctx: Context<FinalizeWindow>, _window_index: u32) -> Result<()> {
    todo!("T1")
}
