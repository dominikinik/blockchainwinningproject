use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::ObservationRecorded,
    logic,
    state::{Deal, DealStatus},
};

/// Accounts of `record_observation`.
#[derive(Accounts)]
pub struct RecordObservation<'info> {
    /// The monitor reporting the round; must be the deal's oracle.
    pub oracle: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = oracle @ DealError::UnauthorizedOracle
    )]
    pub deal: Account<'info, Deal>,
}

/// Adds one monitoring round to the deal's on-chain counters.
///
/// The oracle only reports what it saw (UP or DOWN); it never reports totals or a verdict. Each
/// round can be recorded once. DOWN may be reported as soon as its round starts; UP is accepted only
/// after the round ends. Observations close after the window's grace period.
///
/// # Arguments
///
/// * `ctx` - the `RecordObservation` accounts, already checked against the deal.
/// * `round` - the zero-based round, `0..total_rounds`.
/// * `up` - whether the monitored service was up for the whole round.
///
/// # Returns
///
/// `Ok(())` after the round's bit is set, a counter is incremented and `ObservationRecorded` is
/// emitted.
///
/// # Errors
///
/// * `DealError::DealNotActive` - the provider hasn't locked its guarantee yet.
/// * `DealError::RoundOutOfRange` - `round >= total_rounds`.
/// * `DealError::RoundNotEnded` - the round is still running on the chain clock.
/// * `DealError::ObservationsClosed` - the window and its grace are over.
/// * `DealError::RoundAlreadyRecorded` - the round was recorded before.
pub fn handle_record_observation(ctx: Context<RecordObservation>, round: u32, up: bool) -> Result<()> {
    let deal = &mut ctx.accounts.deal;
    require!(deal.status == DealStatus::Active, DealError::DealNotActive);
    require!(round < deal.total_rounds, DealError::RoundOutOfRange);
    let now = Clock::get()?.unix_timestamp;
    if up {
        require!(logic::round_ended(deal.starts_at, deal.check_interval_seconds, round, now), DealError::RoundNotEnded);
    }
    else {
        require!(logic::round_started(deal.starts_at, deal.check_interval_seconds, round, now), DealError::RoundNotStarted);
    }
    require!(logic::observations_open(deal.starts_at, deal.duration_seconds, now), DealError::ObservationsClosed);
    require!(logic::mark_recorded(&mut deal.recorded, round), DealError::RoundAlreadyRecorded);

    if up {
        deal.up_checks = deal.up_checks.checked_add(1).ok_or(DealError::Overflow)?;
    } else {
        deal.down_checks = deal.down_checks.checked_add(1).ok_or(DealError::Overflow)?;
    }

    emit!(ObservationRecorded {
        deal: deal.key(),
        round,
        up,
        up_checks: deal.up_checks,
        down_checks: deal.down_checks,
    });
    Ok(())
}
