use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::DealError,
    events::DealCancelled,
    logic,
    state::{Deal, DealStatus},
};

/// Accounts of `cancel_deal`. Anchor closes `deal` to `payer` after the handler runs.
#[derive(Accounts)]
pub struct CancelDeal<'info> {
    /// The deal's payer or its recipient.
    pub signer: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = payer,
        has_one = recipient,
        close = payer
    )]
    pub deal: Account<'info, Deal>,
    /// CHECK: must be `deal.payer`; gets its payment and the rent back.
    #[account(mut)]
    pub payer: UncheckedAccount<'info>,
    /// CHECK: must be `deal.recipient`; gets its guarantee back if it had accepted.
    #[account(mut)]
    pub recipient: UncheckedAccount<'info>,
}

/// Returns every deposit to the party that paid it and closes the deal.
///
/// A proposal can be cancelled at any time: the payer withdraws it or the recipient rejects it. An
/// accepted deal can be cancelled by either party only once its window and `CANCEL_TIMEOUT_SECONDS`
/// have passed, so an oracle that never settles can't lock the deposits forever.
///
/// # Arguments
///
/// * `ctx` - the `CancelDeal` accounts, already checked against the deal.
///
/// # Returns
///
/// `Ok(())` after the guarantee (if any) is back with the recipient and `DealCancelled` is emitted;
/// Anchor then closes the deal to the payer, which returns the payment and the rent.
///
/// # Errors
///
/// * `DealError::NotAParty` - the signer is neither the payer nor the recipient.
/// * `DealError::CancelTooEarly` - the deal is accepted and less than `CANCEL_TIMEOUT_SECONDS` have
///   passed since its window ended, so the oracle may still settle.
pub fn handle_cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
    let signer = ctx.accounts.signer.key();
    let deal = &mut ctx.accounts.deal;
    require!(signer == deal.payer || signer == deal.recipient, DealError::NotAParty);

    let guarantee_refunded = match deal.status {
        DealStatus::Proposed => 0,
        DealStatus::Active => {
            let now = Clock::get()?.unix_timestamp;
            require!(logic::cancel_allowed(deal.starts_at, deal.duration_seconds, now), DealError::CancelTooEarly);
            deal.guarantee_lamports
        }
    };
    if guarantee_refunded > 0 {
        deal.sub_lamports(guarantee_refunded)?;
        ctx.accounts.recipient.add_lamports(guarantee_refunded)?;
    }

    emit!(DealCancelled {
        deal: deal.key(),
        payer: deal.payer,
        amount_lamports: deal.amount_lamports,
        cancelled_by: signer,
        guarantee_refunded_lamports: guarantee_refunded,
    });
    Ok(())
}
