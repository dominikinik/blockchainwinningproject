use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::DealSettled,
    logic,
    state::{Deal, DealStatus},
};

/// Accounts of `settle_deal`. Anchor closes `deal` to `payer` after the handler runs.
#[derive(Accounts)]
pub struct SettleDeal<'info> {
    /// Anyone: pays the fee. The outcome doesn't depend on who triggers it.
    pub caller: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = payer,
        has_one = recipient,
        close = payer
    )]
    pub deal: Account<'info, Deal>,
    /// CHECK: must be `deal.payer`; gets the rent back, and the whole escrow on a breach.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    /// CHECK: must be `deal.recipient`; gets the whole escrow when the SLA is met.
    #[account(mut)]
    pub recipient: UncheckedAccount<'info>,
}

/// Judges the SLA from the deal's own counters and pays the whole escrow to the winner.
///
/// Takes no arguments: the verdict is `up_checks * 10_000 >= min_uptime_bps * total_rounds`, read
/// entirely from on-chain state, with unobserved rounds counted as down.
///
/// # Arguments
///
/// * `ctx` - the `SettleDeal` accounts, already checked against the deal.
///
/// # Returns
///
/// `Ok(())` after the payout and `DealSettled`; Anchor then closes the deal to the payer, which
/// returns the rent and, on a breach, the whole escrow.
///
/// # Errors
///
/// * `DealError::DealNotActive` - the provider never locked its guarantee (cancel instead).
/// * `DealError::SettleTooEarly` - the window plus `OBSERVATION_GRACE_SECONDS` hasn't passed.
/// * `DealError::Overflow` - payment plus guarantee overflows a u64.
pub fn handle_settle_deal(ctx: Context<SettleDeal>) -> Result<()> {
    let deal = &mut ctx.accounts.deal;
    require!(deal.status == DealStatus::Active, DealError::DealNotActive);
    let now = Clock::get()?.unix_timestamp;
    require!(logic::settle_allowed(deal.starts_at, deal.duration_seconds, now), DealError::SettleTooEarly);

    let paid_to_recipient = logic::sla_met(deal.up_checks, deal.total_rounds, deal.min_uptime_bps);
    let payout = deal.amount_lamports.checked_add(deal.provider_stake_lamports).ok_or(DealError::Overflow)?;
    if paid_to_recipient {
        // The program owns `deal`, so it can debit it directly.
        deal.sub_lamports(payout)?;
        ctx.accounts.recipient.add_lamports(payout)?;
    }
    // On a breach the escrow stays in `deal` and `close = payer` returns it with the rent.

    emit!(DealSettled {
        deal: deal.key(),
        up_checks: deal.up_checks,
        down_checks: deal.down_checks,
        total_rounds: deal.total_rounds,
        min_uptime_bps: deal.min_uptime_bps,
        paid_to_recipient,
        payout_lamports: payout,
    });
    Ok(())
}
