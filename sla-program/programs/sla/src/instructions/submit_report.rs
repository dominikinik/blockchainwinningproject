use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::SlaError,
    events::ReportSubmitted,
    logic,
    state::{Monitor, MonitorReport, Sla, WindowReport},
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
    ctx: Context<SubmitReport>,
    window_index: u32,
    checked: [u8; 32],
    up: [u8; 32],
) -> Result<()> {
    let sla = &ctx.accounts.sla;
    let authority = ctx.accounts.monitor_authority.key();
    let position = sla
        .monitors
        .iter()
        .position(|m| *m == authority)
        .ok_or(SlaError::NotAssignedMonitor)?;

    let schedule = sla.schedule();
    let now = Clock::get()?.unix_timestamp;
    schedule.check_report_time(window_index, now)?;

    let report = &mut ctx.accounts.window_report;
    // A fresh `init_if_needed` account is all zeroes.
    if report.sla == Pubkey::default() {
        report.sla = sla.key();
        report.window_index = window_index;
        report.payer = authority;
        report.bump = ctx.bumps.window_report;
    }
    require!(!report.per_monitor[position].submitted, SlaError::DuplicateReport);
    logic::validate_bitmaps(&checked, &up, schedule.slot_count(window_index)?)?;
    report.per_monitor[position] = MonitorReport { checked, up, submitted: true };

    let monitor = &mut ctx.accounts.monitor;
    monitor.reports_submitted = monitor
        .reports_submitted
        .checked_add(1)
        .ok_or(SlaError::MathOverflow)?;

    emit!(ReportSubmitted {
        sla: sla.key(),
        window_index,
        monitor: authority,
        checked,
        up,
        timestamp: now,
    });
    Ok(())
}
