use anchor_lang::prelude::*;

use crate::{
    constants::*,
    state::{Monitor, Sla, WindowReport},
};

#[derive(Accounts)]
#[instruction(window_index: u32)]
pub struct SubmitReport<'info> {
    /// Monitor wallet; must be in `sla.monitors`. Pays rent if this report creates the `WindowReport`.
    #[account(mut)]
    pub monitor_authority: Signer<'info>,
    #[account(
        mut,
        seeds = [MONITOR_SEED, monitor_authority.key().as_ref()],
        bump = monitor.bump
    )]
    pub monitor: Account<'info, Monitor>,
    #[account(seeds = [SLA_SEED, sla.customer.as_ref(), sla.sla_id.as_ref()], bump = sla.bump)]
    pub sla: Account<'info, Sla>,
    #[account(
        init_if_needed,
        payer = monitor_authority,
        space = 8 + WindowReport::INIT_SPACE,
        seeds = [WINDOW_SEED, sla.key().as_ref(), window_index.to_le_bytes().as_ref()],
        bump
    )]
    pub window_report: Account<'info, WindowReport>,
    pub system_program: Program<'info, System>,
}

pub fn handle_submit_report(
    _ctx: Context<SubmitReport>,
    _window_index: u32,
    _checked: [u8; 32],
    _up: [u8; 32],
) -> Result<()> {
    todo!("T1")
}
