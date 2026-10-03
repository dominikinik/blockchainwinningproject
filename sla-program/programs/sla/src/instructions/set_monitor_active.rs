use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::SlaError,
    state::{Config, Monitor},
};

#[derive(Accounts)]
pub struct SetMonitorActive<'info> {
    pub admin: Signer<'info>,
    #[account(seeds = [CONFIG_SEED], bump = config.bump, has_one = admin @ SlaError::Unauthorized)]
    pub config: Account<'info, Config>,
    #[account(mut, seeds = [MONITOR_SEED, monitor.authority.as_ref()], bump = monitor.bump)]
    pub monitor: Account<'info, Monitor>,
}

pub fn handle_set_monitor_active(ctx: Context<SetMonitorActive>, active: bool) -> Result<()> {
    ctx.accounts.monitor.active = active;
    Ok(())
}
