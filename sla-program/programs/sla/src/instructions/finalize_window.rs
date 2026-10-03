use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::SlaError,
    events::WindowFinalized,
    instructions::{read_monitor, write_monitor},
    logic,
    state::{Sla, WindowReport, WindowResult},
};

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

pub fn handle_finalize_window(ctx: Context<FinalizeWindow>, window_index: u32) -> Result<()> {
    let sla = &mut ctx.accounts.sla;
    let schedule = sla.schedule();
    schedule.window_bounds(window_index)?;
    require!(
        window_index == sla.next_window_to_finalize,
        SlaError::WindowOutOfOrder
    );
    let now = Clock::get()?.unix_timestamp;
    require!(
        now >= schedule.report_deadline(window_index)?,
        SlaError::ReportDeadlineNotPassed
    );

    // The seeds constraint pins the PDA, and only this program can allocate it, so it is either
    // our `WindowReport` or empty (possibly holding lamports someone sent to the address).
    let report_info = ctx.accounts.window_report.to_account_info();
    let report = if report_info.owner == &crate::ID {
        let report = WindowReport::try_deserialize(&mut &report_info.try_borrow_data()?[..])?;
        require_keys_eq!(ctx.accounts.payer.key(), report.payer, SlaError::PayerMismatch);
        Some(report)
    } else {
        require!(report_info.data_is_empty(), ErrorCode::AccountNotInitialized);
        None
    };

    require!(
        ctx.remaining_accounts.len() == sla.monitors.len(),
        SlaError::MonitorAccountMismatch
    );
    let mut monitors = sla
        .monitors
        .iter()
        .zip(ctx.remaining_accounts)
        .map(|(authority, info)| read_monitor(info, authority, true))
        .collect::<Result<Vec<_>>>()?;

    let ballots: Vec<_> = (0..sla.monitors.len())
        .map(|p| {
            report
                .as_ref()
                .map(|r| r.per_monitor[p])
                .filter(|m| m.submitted)
                .map(|m| (m.checked, m.up))
        })
        .collect();
    let tally = logic::tally_window(
        &ballots,
        schedule.slot_count(window_index)?,
        sla.consensus_required,
    );

    for ((monitor, info), stats) in monitors
        .iter_mut()
        .zip(ctx.remaining_accounts)
        .zip(&tally.agreement)
    {
        monitor.slots_voted = monitor
            .slots_voted
            .checked_add(stats.slots_voted)
            .ok_or(SlaError::MathOverflow)?;
        monitor.slots_agreed = monitor
            .slots_agreed
            .checked_add(stats.slots_agreed)
            .ok_or(SlaError::MathOverflow)?;
        write_monitor(info, monitor)?;
    }

    sla.window_results.push(WindowResult {
        up: tally.up,
        counted: tally.counted,
    });
    sla.up_checks = sla
        .up_checks
        .checked_add(tally.up as u64)
        .ok_or(SlaError::MathOverflow)?;
    sla.counted_checks = sla
        .counted_checks
        .checked_add(tally.counted as u64)
        .ok_or(SlaError::MathOverflow)?;
    sla.next_window_to_finalize += 1;

    if report.is_some() {
        // Close: refund the rent to the payer and hand the account back to the System Program.
        ctx.accounts.payer.add_lamports(report_info.lamports())?;
        **report_info.try_borrow_mut_lamports()? = 0;
        report_info.assign(&System::id());
        report_info.resize(0)?;
    }

    emit!(WindowFinalized {
        sla: sla.key(),
        window_index,
        up: tally.up,
        counted: tally.counted,
        had_reports: report.is_some(),
    });
    Ok(())
}
