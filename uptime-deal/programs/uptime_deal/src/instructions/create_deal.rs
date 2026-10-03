use anchor_lang::{prelude::*, system_program};

use crate::{
    constants::*,
    error::DealError,
    events::DealCreated,
    logic,
    state::{Deal, DealStatus},
};

/// Accounts of `create_deal`.
#[derive(Accounts)]
#[instruction(deal_id: u64)]
pub struct CreateDeal<'info> {
    /// Pays the payment and the deal account's rent.
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: any wallet; only its address is stored. It must accept the deal with `accept_deal`.
    pub recipient: UncheckedAccount<'info>,
    /// CHECK: any key; only its address is stored. The only signer `settle_deal` accepts.
    pub oracle: UncheckedAccount<'info>,
    #[account(
        init,
        payer = payer,
        space = 8 + Deal::INIT_SPACE,
        seeds = [DEAL_SEED, payer.key().as_ref(), &deal_id.to_le_bytes()],
        bump
    )]
    pub deal: Account<'info, Deal>,
    pub system_program: Program<'info, System>,
}

/// Validates the terms, records the proposal and moves the payer's payment into it.
///
/// # Arguments
///
/// * `ctx` - the `CreateDeal` accounts; `deal` has just been created by Anchor.
/// * `deal_id` - payer-chosen id stored in the deal (already used in its seeds).
/// * `amount_lamports` - the payer's payment, moved from the payer into the deal now.
/// * `guarantee_lamports` - the guarantee the recipient must pay when it accepts.
/// * `duration_seconds` - length of the uptime window, which starts when the recipient accepts.
///
/// # Returns
///
/// `Ok(())` after the proposal is filled in, funded, and `DealCreated` is emitted.
///
/// # Errors
///
/// * `DealError::AmountTooSmall` - `amount_lamports < MIN_DEAL_LAMPORTS`.
/// * `DealError::GuaranteeTooSmall` - `guarantee_lamports < MIN_DEAL_LAMPORTS`.
/// * `DealError::RecipientIsPayer` - the payer would make a deal with itself.
/// * `DealError::InvalidDuration` - `duration_seconds` is 0 or above `MAX_DEAL_DURATION_SECONDS`.
/// * A system program error if the payer lacks the lamports.
pub fn handle_create_deal(
    ctx: Context<CreateDeal>,
    deal_id: u64,
    amount_lamports: u64,
    guarantee_lamports: u64,
    duration_seconds: u64,
) -> Result<()> {
    require!(logic::amount_is_valid(amount_lamports), DealError::AmountTooSmall);
    require!(logic::amount_is_valid(guarantee_lamports), DealError::GuaranteeTooSmall);
    require!(logic::duration_is_valid(duration_seconds), DealError::InvalidDuration);
    let payer = ctx.accounts.payer.key();
    let recipient = ctx.accounts.recipient.key();
    require_keys_neq!(payer, recipient, DealError::RecipientIsPayer);

    system_program::transfer(
        CpiContext::new(
            ctx.accounts.system_program.key(),
            system_program::Transfer {
                from: ctx.accounts.payer.to_account_info(),
                to: ctx.accounts.deal.to_account_info(),
            },
        ),
        amount_lamports,
    )?;

    let deal = &mut ctx.accounts.deal;
    deal.payer = payer;
    deal.recipient = recipient;
    deal.oracle = ctx.accounts.oracle.key();
    deal.deal_id = deal_id;
    deal.amount_lamports = amount_lamports;
    deal.guarantee_lamports = guarantee_lamports;
    deal.duration_seconds = duration_seconds;
    deal.accept_deadline = logic::accept_deadline(Clock::get()?.unix_timestamp);
    deal.starts_at = 0;
    deal.status = DealStatus::Proposed;
    deal.bump = ctx.bumps.deal;

    emit!(DealCreated {
        deal: deal.key(),
        payer,
        recipient,
        oracle: deal.oracle,
        amount_lamports,
        guarantee_lamports,
        duration_seconds,
        accept_deadline: deal.accept_deadline,
    });
    Ok(())
}
