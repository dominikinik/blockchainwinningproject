use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::DealSettled,
    logic::{self, UptimeError},
    state::Deal,
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
    /// CHECK: must be `deal.payer`; gets the rent back, and the escrow on a refund.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    /// CHECK: must be `deal.recipient`; gets the escrow when uptime is above 99%.
    #[account(mut)]
    pub recipient: UncheckedAccount<'info>,
}

/// Judges the reported uptime and pays the escrow to the recipient or back to the payer.
///
/// # Arguments
///
/// * `ctx` - the `SettleDeal` accounts, already checked against the deal.
/// * `up_seconds` - seconds the application was up during the period.
/// * `total_seconds` - length of the period in seconds.
///
/// # Returns
///
/// `Ok(())` after the payout and `DealSettled`; Anchor then closes the deal to the payer,
/// which returns the rent and, on a refund, the escrow.
///
/// # Errors
///
/// * `DealError::InvalidUptime` - `total_seconds == 0` or `up_seconds > total_seconds`.
pub fn handle_settle_deal(ctx: Context<SettleDeal>, up_seconds: u64, total_seconds: u64) -> Result<()> {
    let paid_to_recipient = logic::uptime_above_threshold(up_seconds, total_seconds)
        .map_err(|e: UptimeError| {
            msg!("invalid uptime: {:?}", e);
            DealError::InvalidUptime
        })?;

    let deal = &mut ctx.accounts.deal;
    let amount = deal.amount_lamports;
    if paid_to_recipient {
        // The program owns `deal`, so it can debit it directly.
        deal.sub_lamports(amount)?;
        ctx.accounts.recipient.add_lamports(amount)?;
    }
    // On a refund the escrow stays in `deal` and `close = payer` returns it with the rent.

    emit!(DealSettled {
        deal: deal.key(),
        up_seconds,
        total_seconds,
        paid_to_recipient,
        amount_lamports: amount,
    });
    Ok(())
}
