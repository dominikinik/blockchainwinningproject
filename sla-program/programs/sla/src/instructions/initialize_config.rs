use anchor_lang::prelude::*;

use crate::{constants::*, state::Config};

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Debug)]
pub struct ConfigParams {
    /// Window length; `DEFAULT_WINDOW_SECS` (3600) outside of tests and demos.
    pub window_secs: u32,
    /// How long after a window ends monitors may still report; default 600.
    pub report_grace_secs: u32,
    /// 1..=MAX_MONITORS_PER_SLA; default 5.
    pub max_monitors_per_sla: u8,
}

#[derive(Accounts)]
pub struct InitializeConfig<'info> {
    #[account(mut)]
    pub admin: Signer<'info>,
    #[account(
        init,
        payer = admin,
        space = 8 + Config::INIT_SPACE,
        seeds = [CONFIG_SEED],
        bump
    )]
    pub config: Account<'info, Config>,
    pub system_program: Program<'info, System>,
}

pub fn handle_initialize_config(_ctx: Context<InitializeConfig>, _params: ConfigParams) -> Result<()> {
    todo!("T1")
}
