//! `uptime_deal`: a simple uptime-conditioned payment between two accounts.
//!
//! A payer locks lamports in a `Deal` account and names a recipient and an uptime oracle.
//! When the oracle settles the deal with the measured uptime, the recipient receives the
//! locked amount if uptime is strictly above 99%; otherwise the payer gets it back. If the oracle
//! never settles, the payer can cancel the deal once its window and a timeout have passed.
use anchor_lang::prelude::*;

pub mod constants;
pub mod error;
pub mod events;
pub mod instructions;
pub mod logic;
pub mod state;

pub use constants::*;
pub use instructions::*;

declare_id!("EesKoTPMwuRzvpfuZqNbyEf7mMrjUNXGCa2ugHAeVx2r");

#[program]
pub mod uptime_deal {
    use super::*;

    /// Opens a deal and locks the payment in escrow.
    ///
    /// Moves `amount_lamports` from the payer into the new `Deal` PDA, which records the
    /// payer, the recipient and the oracle that may later settle it.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `payer` (signer, funds the escrow and the rent), `recipient`
    ///   (paid when uptime is above 99%), `oracle` (the only key allowed to settle), the
    ///   `deal` PDA to create (seeds `["deal", payer, deal_id]`) and the system program.
    /// * `deal_id` - payer-chosen number that makes the deal address unique per payer.
    /// * `amount_lamports` - lamports to lock; at least `MIN_DEAL_LAMPORTS`.
    /// * `duration_seconds` - length of the uptime window, which starts at the current chain
    ///   time; 1 to `MAX_DEAL_DURATION_SECONDS`.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the deal exists and holds the escrow; emits `DealCreated`.
    ///
    /// # Errors
    ///
    /// * `DealError::AmountTooSmall` - `amount_lamports` is below `MIN_DEAL_LAMPORTS`.
    /// * `DealError::RecipientIsPayer` - the recipient is the payer.
    /// * `DealError::InvalidDuration` - `duration_seconds` is out of range.
    /// * Anchor/system errors if the deal already exists or the payer cannot pay.
    pub fn create_deal(
        ctx: Context<CreateDeal>,
        deal_id: u64,
        amount_lamports: u64,
        duration_seconds: u64,
    ) -> Result<()> {
        instructions::create_deal::handle_create_deal(ctx, deal_id, amount_lamports, duration_seconds)
    }

    /// Settles a deal from the measured uptime and closes it.
    ///
    /// Pays the escrow to the recipient when `up_seconds / total_seconds` is strictly above
    /// 99%; otherwise refunds it to the payer. The `Deal` account is closed and its rent
    /// returns to the payer either way.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `oracle` (signer, must be the deal's oracle), the `deal` PDA, and
    ///   the deal's `payer` and `recipient` wallets (both writable).
    /// * `up_seconds` - seconds the application was up during the measured period.
    /// * `total_seconds` - length of the measured period in seconds; must be positive.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the escrow is paid out and the deal is closed; emits `DealSettled`.
    ///
    /// # Errors
    ///
    /// * `DealError::InvalidUptime` - `total_seconds` is 0 or `up_seconds > total_seconds`.
    /// * `DealError::UnauthorizedOracle` - the signer is not the deal's oracle.
    /// * Anchor constraint errors if `payer`/`recipient` don't match the deal.
    pub fn settle_deal(ctx: Context<SettleDeal>, up_seconds: u64, total_seconds: u64) -> Result<()> {
        instructions::settle_deal::handle_settle_deal(ctx, up_seconds, total_seconds)
    }

    /// Returns the escrow of a deal the oracle never settled to its payer and closes it.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `payer` (signer, must be the deal's payer) and the `deal` PDA.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the escrow and the rent are back with the payer; emits `DealCancelled`.
    ///
    /// # Errors
    ///
    /// * `DealError::CancelTooEarly` - the chain time is before
    ///   `starts_at + duration_seconds + CANCEL_TIMEOUT_SECONDS`.
    /// * Anchor constraint errors if the signer isn't the deal's payer.
    pub fn cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
        instructions::cancel_deal::handle_cancel_deal(ctx)
    }
}
