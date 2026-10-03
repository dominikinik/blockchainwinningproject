use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::DealCancelled,
    state::{Deal, DealStatus},
};

/// Accounts of `cancel_deal`. Anchor closes `deal` to `payer` after the handler runs.
#[derive(Accounts)]
pub struct CancelDeal<'info> {
    /// Funded the deal; gets the payment and the rent back.
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

/// Lets the payer withdraw a deal the provider never accepted.
///
/// An active deal can't be cancelled: anyone can settle it once its window is over, so the escrow
/// is never stuck.
///
/// # Arguments
///
/// * `ctx` - the `CancelDeal` accounts, already checked against the deal.
///
/// # Returns
///
/// `Ok(())` after `DealCancelled`; Anchor then closes the deal to the payer, which returns the
/// payment and the rent.
///
/// # Errors
///
/// * `DealError::DealAlreadyActive` - the provider has locked its guarantee and the window runs.
pub fn handle_cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
    let deal = &ctx.accounts.deal;
    require!(deal.status == DealStatus::AwaitingProvider, DealError::DealAlreadyActive);

    emit!(DealCancelled {
        deal: deal.key(),
        payer: deal.payer,
        amount_lamports: deal.amount_lamports,
    });
    Ok(())
}
