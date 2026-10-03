use anchor_lang::prelude::*;

use crate::{
    constants::*,
    error::SlaError,
    events::SlaSettled,
    logic,
    state::{Recipient, Sla},
};

#[derive(Accounts)]
pub struct Settle<'info> {
    #[account(
        mut,
        seeds = [SLA_SEED, sla.customer.as_ref(), sla.sla_id.as_ref()],
        bump = sla.bump
    )]
    pub sla: Account<'info, Sla>,
    /// CHECK: must be `sla.customer`; receives the escrow on a refund.
    #[account(mut, address = sla.customer)]
    pub customer: UncheckedAccount<'info>,
    /// CHECK: must be `sla.provider`; receives the escrow when the target was met.
    #[account(mut, address = sla.provider)]
    pub provider: UncheckedAccount<'info>,
}

pub fn handle_settle(ctx: Context<Settle>) -> Result<()> {
    let sla = &mut ctx.accounts.sla;
    require!(!sla.settled, SlaError::AlreadySettled);
    let settle_from = sla
        .end_ts
        .checked_add(sla.report_grace_secs as i64)
        .ok_or(SlaError::MathOverflow)?;
    require!(
        Clock::get()?.unix_timestamp >= settle_from,
        SlaError::NotYetSettleable
    );
    require!(
        sla.next_window_to_finalize == sla.total_windows,
        SlaError::WindowsNotFinalized
    );

    let paid = logic::provider_is_paid(sla.up_checks, sla.counted_checks, sla.required_uptime_bps);
    let (recipient, destination) = if paid {
        (Recipient::Provider, ctx.accounts.provider.to_account_info())
    } else {
        (Recipient::Customer, ctx.accounts.customer.to_account_info())
    };
    // The program owns `sla`, so it can debit it directly. The rent-exempt reserve stays.
    let amount = sla.escrow_lamports;
    sla.sub_lamports(amount)?;
    destination.add_lamports(amount)?;

    sla.settled = true;
    sla.recipient = Some(recipient);

    emit!(SlaSettled {
        sla: sla.key(),
        recipient,
        amount_lamports: amount,
        up_checks: sla.up_checks,
        counted_checks: sla.counted_checks,
    });
    Ok(())
}
