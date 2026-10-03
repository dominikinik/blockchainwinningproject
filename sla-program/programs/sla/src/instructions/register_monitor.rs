use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::SlaError,
    logic,
    state::{Config, Monitor},
};

#[derive(Accounts)]
pub struct RegisterMonitor<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = admin @ SlaError::Unauthorized)]
    pub config: Account<'info, Config>,
    /// CHECK: the monitor node's wallet; only its key is stored.
    pub authority: UncheckedAccount<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Monitor::INIT_SPACE,
        seeds = [MONITOR_SEED, authority.key().as_ref()],
        bump
    )]
    pub monitor: Account<'info, Monitor>,
    pub system_program: Program<'info, System>,
}

pub fn handle_register_monitor(ctx: Context<RegisterMonitor>, name: String) -> Result<()> {
    logic::validate_monitor_name(&name)?;
    ctx.accounts.monitor.set_inner(Monitor {
        authority: ctx.accounts.authority.key(),
        name,
        active: true,
        reports_submitted: 0,
        slots_voted: 0,
        slots_agreed: 0,
        bump: ctx.bumps.monitor,
    });
    Ok(())
}
