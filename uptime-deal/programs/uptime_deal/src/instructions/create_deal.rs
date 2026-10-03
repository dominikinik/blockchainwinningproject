use anchor_lang::{prelude::*, system_program};

use crate::{constants::*, error::DealError, events::DealCreated, logic, state::Deal};

/// Accounts of `create_deal`.
#[derive(Accounts)]
#[instruction(deal_id: u64)]
pub struct CreateDeal<'info> {
    /// Funds the escrow and the deal account's rent.
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: any wallet; only its address is stored. Paid when uptime is above 99%.
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

/// Validates the terms, records the deal and moves the escrow into it.
///
/// # Arguments
///
/// * `ctx` - the `CreateDeal` accounts; `deal` has just been created by Anchor.
/// * `deal_id` - payer-chosen id stored in the deal (already used in its seeds).
/// * `amount_lamports` - lamports to move from the payer into the deal.
///
/// # Returns
///
/// `Ok(())` after the deal is filled in, funded, and `DealCreated` is emitted.
///
/// # Errors
///
/// * `DealError::AmountTooSmall` - `amount_lamports < MIN_DEAL_LAMPORTS`.
/// * `DealError::RecipientIsPayer` - the payer would pay itself.
/// * A system program error if the payer lacks the lamports.
pub fn handle_create_deal(ctx: Context<CreateDeal>, deal_id: u64, amount_lamports: u64) -> Result<()> {
    require!(logic::amount_is_valid(amount_lamports), DealError::AmountTooSmall);
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
    deal.bump = ctx.bumps.deal;

    emit!(DealCreated {
        deal: deal.key(),
        payer,
        recipient,
        oracle: deal.oracle,
        amount_lamports,
    });
    Ok(())
}
