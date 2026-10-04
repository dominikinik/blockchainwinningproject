use anchor_lang::{prelude::*, system_program};

use crate::{
    constants::*,
    error::DealError,
    events::DealAccepted,
    logic::{self, Terms},
    state::{Deal, DealStatus},
};

/// Accounts of `accept_deal`.
#[derive(Accounts)]
pub struct AcceptDeal<'info> {
    /// The deal's recipient; pays the guarantee.
    #[account(mut)]
    pub recipient: Signer<'info>,
    #[account(
        mut,
        seeds = [DEAL_SEED, deal.payer.as_ref(), &deal.deal_id.to_le_bytes()],
        bump = deal.bump,
        has_one = recipient
    )]
    pub deal: Account<'info, Deal>,
    pub system_program: Program<'info, System>,
}

/// Accepts a proposal: checks the terms the recipient signed, locks its guarantee and starts the window.
///
/// # Arguments
///
/// * `ctx` - the `AcceptDeal` accounts, already checked against the deal.
/// * `signed` - the terms the recipient agreed to; they must equal the deal's terms.
///
/// # Returns
///
/// `Ok(())` after the guarantee is locked, the deal is `Active` from the current chain time, and
/// `DealAccepted` is emitted.
///
/// # Errors
///
/// * `DealError::DealNotProposed` - the deal was already accepted.
/// * `DealError::AcceptExpired` - the chain time is at or past `accept_deadline`.
/// * `DealError::TermsMismatch` - `signed` differs from the deal's terms.
/// * A system program error if the recipient lacks the lamports.
pub fn handle_accept_deal(ctx: Context<AcceptDeal>, signed: Terms) -> Result<()> {
    let deal = &ctx.accounts.deal;
    require!(deal.status == DealStatus::Proposed, DealError::DealNotProposed);
    let now = Clock::get()?.unix_timestamp;
    require!(logic::accept_open(deal.accept_deadline, now), DealError::AcceptExpired);
    let stored = Terms {
        amount_lamports: deal.amount_lamports,
        guarantee_lamports: deal.guarantee_lamports,
        duration_seconds: deal.duration_seconds,
        oracle: deal.oracle.to_bytes(),
    };
    require!(logic::terms_match(&stored, &signed), DealError::TermsMismatch);

    system_program::transfer(
        CpiContext::new(
            ctx.accounts.system_program.key(),
            system_program::Transfer {
                from: ctx.accounts.recipient.to_account_info(),
                to: ctx.accounts.deal.to_account_info(),
            },
        ),
        stored.guarantee_lamports,
    )?;

    let deal = &mut ctx.accounts.deal;
    deal.starts_at = now;
    deal.status = DealStatus::Active;

    emit!(DealAccepted {
        deal: deal.key(),
        recipient: deal.recipient,
        guarantee_lamports: deal.guarantee_lamports,
        starts_at: now,
        duration_seconds: deal.duration_seconds,
    });
    Ok(())
}
