use anchor_lang::prelude::*;

use crate::{
    constants::*,
    state::{Config, Sla},
};

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct CreateSlaParams {
    pub provider: Pubkey,
    pub name: String,
    /// https:// URL the monitors check.
    pub endpoint: String,
    /// Moved from the customer into the `Sla` account.
    pub escrow_lamports: u64,
    /// 1..=10_000; 9_990 = 99.90%.
    pub required_uptime_bps: u16,
    /// `end_ts = start_ts + duration_secs`, where `start_ts` is the clock at creation.
    pub duration_secs: u32,
    pub check_interval_secs: u32,
    pub timeout_ms: u32,
    pub consensus_required: u8,
}

/// Remaining accounts: the `Monitor` PDA of every entry in `monitors`, in the same order
/// (read-only). Each must be active.
#[derive(Accounts)]
#[instruction(sla_id: [u8; 16], params: CreateSlaParams)]
pub struct CreateSla<'info> {
    #[account(mut)]
    pub customer: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump)]
    pub config: Account<'info, Config>,
    #[account(
        init,
        payer = customer,
        space = Sla::space(Sla::window_count(params.duration_secs, config.window_secs).min(MAX_WINDOWS)),
        seeds = [SLA_SEED, customer.key().as_ref(), sla_id.as_ref()],
        bump
    )]
    pub sla: Account<'info, Sla>,
    pub system_program: Program<'info, System>,
}

pub fn handle_create_sla(
    _ctx: Context<CreateSla>,
    _sla_id: [u8; 16],
    _params: CreateSlaParams,
    _monitors: Vec<Pubkey>,
) -> Result<()> {
    todo!("T1")
}
