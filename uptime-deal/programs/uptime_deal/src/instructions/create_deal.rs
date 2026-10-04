use anchor_lang::{prelude::*, system_program};

use crate::{
    constants::*,
    error::DealError,
    events::{DealCreated, DealStarted},
    logic,
    state::{Deal, DealStatus},
};

/// Accounts of `create_deal`.
#[derive(Accounts)]
#[instruction(deal_id: u64, amount_lamports: u64, provider_stake_lamports: u64, duration_seconds: u64, check_interval_seconds: u64)]
pub struct CreateDeal<'info> {
    /// The customer: funds the payment and the deal account's rent.
    #[account(mut)]
    pub payer: Signer<'info>,
    /// CHECK: any wallet; only its address is stored. The provider, paid when the SLA is met.
    pub recipient: UncheckedAccount<'info>,
    /// CHECK: any key; only its address is stored. The only signer `record_observation` accepts.
    pub oracle: UncheckedAccount<'info>,
    #[account(
        init,
        payer = payer,
        // Invalid terms size the account for zero rounds; the handler then rejects them.
        space = Deal::space(logic::total_rounds(duration_seconds, check_interval_seconds).unwrap_or(0)),
        seeds = [DEAL_SEED, payer.key().as_ref(), &deal_id.to_le_bytes()],
        bump
    )]
    pub deal: Account<'info, Deal>,
    pub system_program: Program<'info, System>,
}

/// Validates the terms, records the deal and moves the customer payment into it.
///
/// # Arguments
///
/// * `ctx` - the `CreateDeal` accounts; `deal` has just been created by Anchor.
/// * `deal_id` - payer-chosen id stored in the deal (already used in its seeds).
/// * `amount_lamports` - lamports to move from the payer into the deal.
/// * `provider_stake_lamports` - guarantee the recipient must lock with `accept_deal`; 0 starts the
///   window now.
/// * `duration_seconds` - length of the uptime window.
/// * `check_interval_seconds` - length of one monitoring round; must divide `duration_seconds`.
/// * `min_uptime_bps` - required uptime in basis points.
///
/// # Returns
///
/// `Ok(())` after the deal is filled in, funded, and `DealCreated` (plus `DealStarted` when there is
/// no guarantee) is emitted.
///
/// # Errors
///
/// * `DealError::AmountTooSmall` - `amount_lamports < MIN_DEAL_LAMPORTS`.
/// * `DealError::RecipientIsPayer` - the payer would pay itself.
/// * `DealError::InvalidDuration` - `duration_seconds` is 0 or above `MAX_DEAL_DURATION_SECONDS`.
/// * `DealError::InvalidCheckInterval` - the interval doesn't split the window into 1..`MAX_ROUNDS`.
/// * `DealError::InvalidThreshold` - `min_uptime_bps` is 0 or above 10,000.
/// * A system program error if the payer lacks the lamports.
pub fn handle_create_deal(
    ctx: Context<CreateDeal>,
    deal_id: u64,
    amount_lamports: u64,
    provider_stake_lamports: u64,
    duration_seconds: u64,
    check_interval_seconds: u64,
    min_uptime_bps: u16,
) -> Result<()> {
    require!(logic::amount_is_valid(amount_lamports), DealError::AmountTooSmall);
    require!(logic::duration_is_valid(duration_seconds), DealError::InvalidDuration);
    let total_rounds =
        logic::total_rounds(duration_seconds, check_interval_seconds).ok_or(DealError::InvalidCheckInterval)?;
    require!(logic::threshold_is_valid(min_uptime_bps), DealError::InvalidThreshold);
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
    deal.provider_stake_lamports = provider_stake_lamports;
    deal.accept_deadline = Clock::get()?.unix_timestamp
        .checked_add(ACCEPT_TIMEOUT_SECONDS)
        .ok_or(DealError::Overflow)?;
    deal.duration_seconds = duration_seconds;
    deal.check_interval_seconds = check_interval_seconds;
    deal.min_uptime_bps = min_uptime_bps;
    deal.total_rounds = total_rounds;
    deal.up_checks = 0;
    deal.down_checks = 0;
    deal.bump = ctx.bumps.deal;
    deal.recorded = vec![0; (total_rounds as usize).div_ceil(8)];

    emit!(DealCreated {
        deal: deal.key(),
        payer,
        recipient,
        oracle: deal.oracle,
        amount_lamports,
        provider_stake_lamports,
        duration_seconds,
        check_interval_seconds,
        min_uptime_bps,
        total_rounds,
        accept_deadline: deal.accept_deadline,
    });

    if provider_stake_lamports == 0 {
        start(deal)?;
    } else {
        deal.status = DealStatus::AwaitingProvider;
        deal.starts_at = 0;
    }
    Ok(())
}

/// Activates a deal: its window starts at the current chain time.
///
/// # Arguments
///
/// * `deal` - the deal to start.
///
/// # Returns
///
/// `Ok(())` after `status`, `starts_at` and `DealStarted` are set.
///
/// # Errors
///
/// A sysvar error if the clock can't be read.
pub fn start(deal: &mut Account<Deal>) -> Result<()> {
    deal.status = DealStatus::Active;
    deal.starts_at = Clock::get()?.unix_timestamp;
    emit!(DealStarted {
        deal: deal.key(),
        starts_at: deal.starts_at,
        ends_at: deal.starts_at.saturating_add(deal.duration_seconds as i64),
    });
    Ok(())
}
