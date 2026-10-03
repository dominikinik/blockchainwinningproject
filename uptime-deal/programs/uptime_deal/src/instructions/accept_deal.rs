use anchor_lang::{prelude::*, system_program};

use crate::{
    constants::*,
    error::DealError,
    instructions::create_deal::start,
    state::{Deal, DealStatus},
};

/// Accounts of `accept_deal`.
#[derive(Accounts)]
pub struct AcceptDeal<'info> {
    /// The provider: locks its guarantee.
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

/// Locks the provider's guarantee and starts the window.
///
/// # Arguments
///
/// * `ctx` - the `AcceptDeal` accounts, already checked against the deal.
///
/// # Returns
///
/// `Ok(())` after the guarantee is in the deal and `DealStarted` is emitted.
///
/// # Errors
///
/// * `DealError::DealAlreadyActive` - the deal has already started.
/// * A system program error if the recipient lacks the lamports.
pub fn handle_accept_deal(ctx: Context<AcceptDeal>) -> Result<()> {
    require!(ctx.accounts.deal.status == DealStatus::AwaitingProvider, DealError::DealAlreadyActive);

    system_program::transfer(
        CpiContext::new(
            ctx.accounts.system_program.key(),
            system_program::Transfer {
                from: ctx.accounts.recipient.to_account_info(),
                to: ctx.accounts.deal.to_account_info(),
            },
        ),
        ctx.accounts.deal.provider_stake_lamports,
    )?;
    start(&mut ctx.accounts.deal)
}
