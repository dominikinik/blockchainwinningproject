use anchor_lang::{prelude::*, system_program};

use crate::{
    constants::*,
    error::SlaError,
    events::SlaCreated,
    instructions::read_monitor,
    logic::{self, SlaTerms},
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
    ctx: Context<CreateSla>,
    sla_id: [u8; 16],
    params: CreateSlaParams,
    monitors: Vec<Pubkey>,
) -> Result<()> {
    let config = &ctx.accounts.config;
    let customer = ctx.accounts.customer.key();

    require!(
        !monitors.is_empty() && monitors.len() <= config.max_monitors_per_sla as usize,
        SlaError::InvalidMonitorCount
    );
    for (i, m) in monitors.iter().enumerate() {
        require!(!monitors[..i].contains(m), SlaError::DuplicateMonitor);
    }
    require!(
        ctx.remaining_accounts.len() == monitors.len(),
        SlaError::MonitorAccountMismatch
    );
    let registered = monitors
        .iter()
        .zip(ctx.remaining_accounts)
        .map(|(authority, info)| read_monitor(info, authority, false))
        .collect::<Result<Vec<_>>>()?;
    require!(registered.iter().all(|m| m.active), SlaError::MonitorInactive);

    let total_windows = logic::validate_sla_terms(&SlaTerms {
        monitor_count: monitors.len(),
        consensus_required: params.consensus_required,
        name: &params.name,
        endpoint: &params.endpoint,
        escrow_lamports: params.escrow_lamports,
        required_uptime_bps: params.required_uptime_bps,
        duration_secs: params.duration_secs,
        check_interval_secs: params.check_interval_secs,
        timeout_ms: params.timeout_ms,
        window_secs: config.window_secs,
    })?;
    require_keys_neq!(params.provider, customer, SlaError::ProviderIsCustomer);
    require_keys_neq!(params.provider, Pubkey::default(), SlaError::InvalidParams);

    let start_ts = Clock::get()?.unix_timestamp;
    let end_ts = start_ts
        .checked_add(params.duration_secs as i64)
        .ok_or(SlaError::MathOverflow)?;
    let (window_secs, report_grace_secs) = (config.window_secs, config.report_grace_secs);

    system_program::transfer(
        CpiContext::new(
            ctx.accounts.system_program.key(),
            system_program::Transfer {
                from: ctx.accounts.customer.to_account_info(),
                to: ctx.accounts.sla.to_account_info(),
            },
        ),
        params.escrow_lamports,
    )?;

    let sla = &mut ctx.accounts.sla;
    sla.set_inner(Sla {
        customer,
        provider: params.provider,
        sla_id,
        monitors: monitors.clone(),
        name: params.name,
        endpoint: params.endpoint,
        escrow_lamports: params.escrow_lamports,
        required_uptime_bps: params.required_uptime_bps,
        start_ts,
        end_ts,
        check_interval_secs: params.check_interval_secs,
        timeout_ms: params.timeout_ms,
        consensus_required: params.consensus_required,
        window_secs,
        report_grace_secs,
        total_windows,
        next_window_to_finalize: 0,
        up_checks: 0,
        counted_checks: 0,
        window_results: Vec::new(),
        settled: false,
        recipient: None,
        bump: ctx.bumps.sla,
    });

    emit!(SlaCreated {
        sla: sla.key(),
        customer,
        provider: params.provider,
        sla_id,
        escrow_lamports: params.escrow_lamports,
        required_uptime_bps: params.required_uptime_bps,
        start_ts,
        end_ts,
        total_windows,
        monitors,
    });
    Ok(())
}
