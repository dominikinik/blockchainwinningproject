use anchor_lang::prelude::*;

use crate::{constants::*, state::Sla};

#[derive(Accounts)]
pub struct Settle<'info> {
    #[account(
        mut,
        seeds = [SLA_SEED, sla.customer.as_ref(), sla.sla_id.as_ref()],
        bump = sla.bump
    )]
    pub sla: Account<'info, Sla>,
    /// CHECK: must be `sla.customer`; receives the escrow on a refund.
    #[account(mut, address = sla.customer)]
    pub customer: UncheckedAccount<'info>,
    /// CHECK: must be `sla.provider`; receives the escrow when the target was met.
    #[account(mut, address = sla.provider)]
    pub provider: UncheckedAccount<'info>,
}

pub fn handle_settle(_ctx: Context<Settle>) -> Result<()> {
    todo!("T1")
}
