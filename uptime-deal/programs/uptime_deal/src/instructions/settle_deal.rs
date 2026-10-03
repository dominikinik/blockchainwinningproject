use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::DealSettled,
    logic::{self, UptimeError},
    state::{Deal, DealStatus},
};

/// Accounts of `settle_deal`. Anchor closes `deal` to `payer` after the handler runs.
#[derive(Accounts)]
pub struct SettleDeal<'info> {
    /// Reports the uptime; must be the deal's oracle.
    pub oracle: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = oracle @ DealError::UnauthorizedOracle,
        has_one = payer,
        has_one = recipient,
        close = payer
    )]
    pub deal: Account<'info, Deal>,
    /// CHECK: must be `deal.payer`; gets the rent back, and both deposits when uptime is 99% or less.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    /// CHECK: must be `deal.recipient`; gets both deposits when uptime is above 99%.
    #[account(mut)]
    pub recipient: UncheckedAccount<'info>,
}

/// Judges the reported uptime and pays both deposits to the recipient or to the payer.
///
/// # Arguments
///
/// * `ctx` - the `SettleDeal` accounts, already checked against the deal.
/// * `up_seconds` - seconds the application was up during the period.
/// * `total_seconds` - length of the period in seconds.
///
/// # Returns
///
/// `Ok(())` after the payout and `DealSettled`; Anchor then closes the deal to the payer, which
/// returns the rent and, when uptime was 99% or less, both deposits.
///
/// # Errors
///
/// * `DealError::DealNotActive` - the recipient hasn't accepted the deal, so no window was measured.
/// * `DealError::InvalidUptime` - `total_seconds == 0` or `up_seconds > total_seconds`.
pub fn handle_settle_deal(ctx: Context<SettleDeal>, up_seconds: u64, total_seconds: u64) -> Result<()> {
    require!(ctx.accounts.deal.status == DealStatus::Active, DealError::DealNotActive);
    let paid_to_recipient = logic::uptime_above_threshold(up_seconds, total_seconds)
        .map_err(|e: UptimeError| {
            msg!("invalid uptime: {:?}", e);
            DealError::InvalidUptime
        })?;

    let deal = &mut ctx.accounts.deal;
    let (amount, guarantee) = (deal.amount_lamports, deal.guarantee_lamports);
    if paid_to_recipient {
        // The program owns `deal`, so it can debit it directly.
        let both = amount.checked_add(guarantee).ok_or(ProgramError::ArithmeticOverflow)?;
        deal.sub_lamports(both)?;
        ctx.accounts.recipient.add_lamports(both)?;
    }
    // Otherwise both deposits stay in `deal` and `close = payer` returns them with the rent.

    emit!(DealSettled {
        deal: deal.key(),
        up_seconds,
        total_seconds,
        paid_to_recipient,
        amount_lamports: amount,
        guarantee_lamports: guarantee,
    });
    Ok(())
}
