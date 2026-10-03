use anchor_lang::prelude::*;

use crate::{constants::*, error::DealError, events::DealCancelled, logic, state::Deal};

/// Accounts of `cancel_deal`. Anchor closes `deal` to `payer` after the handler runs.
#[derive(Accounts)]
pub struct CancelDeal<'info> {
    /// Funded the deal; gets the escrow and the rent back.
    #[account(mut)]
    pub payer: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = payer,
        close = payer
    )]
    pub deal: Account<'info, Deal>,
}

/// Lets the payer take back a deal the oracle never settled.
///
/// # Arguments
///
/// * `ctx` - the `CancelDeal` accounts, already checked against the deal.
///
/// # Returns
///
/// `Ok(())` after `DealCancelled`; Anchor then closes the deal to the payer, which returns the
/// escrow and the rent.
///
/// # Errors
///
/// * `DealError::CancelTooEarly` - less than `CANCEL_TIMEOUT_SECONDS` have passed since the
///   window ended, so the oracle may still settle.
pub fn handle_cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
    let deal = &ctx.accounts.deal;
    let now = Clock::get()?.unix_timestamp;
    require!(logic::cancel_allowed(deal.starts_at, deal.duration_seconds, now), DealError::CancelTooEarly);

    emit!(DealCancelled {
        deal: deal.key(),
        payer: deal.payer,
        amount_lamports: deal.amount_lamports,
    });
    Ok(())
}
