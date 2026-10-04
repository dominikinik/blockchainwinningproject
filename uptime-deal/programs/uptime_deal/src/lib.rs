//! `uptime_deal`: an uptime SLA whose outcome is decided on chain.
//!
//! A customer (payer) locks a payment in a `Deal` account and names a provider (recipient), an
//! uptime monitor (oracle), the window, the monitoring interval and the required uptime. The
//! provider may have to lock a guarantee too. The monitor only reports, round by round, whether
//! the service was UP or DOWN; the program keeps the counters. Once the window is over, anyone can
//! call `settle_deal`, and the program itself compares the counters with the threshold and pays
//! the whole escrow to the provider (SLA met) or to the customer (breach).
//! Monitors observe; the program decides.
use anchor_lang::prelude::*;

pub mod constants;
pub mod error;
pub mod events;
pub mod instructions;
pub mod logic;
pub mod state;

pub use constants::*;
pub use instructions::*;

declare_id!("FVYpjzHktSRkPeUMytDPwVnAXTEf1QqVymKTnqNqfDmr");

#[program]
pub mod uptime_deal {
    use super::*;

    /// Opens a deal and locks the customer payment in escrow.
    ///
    /// Moves `amount_lamports` from the payer into the new `Deal` PDA, which records the parties,
    /// the terms and an empty set of monitoring counters. Without a provider guarantee the window
    /// starts now; with one it starts when the recipient calls `accept_deal`.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `payer` (signer, funds the payment and the rent), `recipient` (the
    ///   provider), `oracle` (the only key allowed to record observations), the `deal` PDA to
    ///   create (seeds `["deal", payer, deal_id]`) and the system program.
    /// * `deal_id` - payer-chosen number that makes the deal address unique per payer.
    /// * `amount_lamports` - the customer payment; at least `MIN_DEAL_LAMPORTS`.
    /// * `provider_stake_lamports` - the provider guarantee to lock with `accept_deal`; 0 for none.
    /// * `duration_seconds` - length of the uptime window; 1 to `MAX_DEAL_DURATION_SECONDS`.
    /// * `check_interval_seconds` - length of one monitoring round; must divide the window into 1
    ///   to `MAX_ROUNDS` rounds.
    /// * `min_uptime_bps` - required uptime in basis points (1 to 10,000).
    ///
    /// # Returns
    ///
    /// `Ok(())` once the deal exists and holds the payment; emits `DealCreated`, and `DealStarted`
    /// when there is no guarantee.
    ///
    /// # Errors
    ///
    /// * `DealError::AmountTooSmall` - `amount_lamports` is below `MIN_DEAL_LAMPORTS`.
    /// * `DealError::RecipientIsPayer` - the recipient is the payer.
    /// * `DealError::InvalidDuration` - `duration_seconds` is out of range.
    /// * `DealError::InvalidCheckInterval` - the interval doesn't fit the window.
    /// * `DealError::InvalidThreshold` - `min_uptime_bps` is out of range.
    /// * Anchor/system errors if the deal already exists or the payer cannot pay.
    pub fn create_deal(
        ctx: Context<CreateDeal>,
        deal_id: u64,
        amount_lamports: u64,
        provider_stake_lamports: u64,
        duration_seconds: u64,
        check_interval_seconds: u64,
        min_uptime_bps: u16,
    ) -> Result<()> {
        instructions::create_deal::handle_create_deal(
            ctx,
            deal_id,
            amount_lamports,
            provider_stake_lamports,
            duration_seconds,
            check_interval_seconds,
            min_uptime_bps,
        )
    }

    /// Locks the provider guarantee and starts the window.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `recipient` (signer, must be the deal's recipient), the `deal` PDA and
    ///   the system program.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the guarantee is locked; emits `DealStarted`.
    ///
    /// # Errors
    ///
    /// * `DealError::DealAlreadyActive` - the deal has already started.
    /// * Anchor constraint errors if the signer isn't the deal's recipient.
    pub fn accept_deal(ctx: Context<AcceptDeal>) -> Result<()> {
        instructions::accept_deal::handle_accept_deal(ctx)
    }

    /// Records the monitor's UP/DOWN observation of one round in the deal's counters.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `oracle` (signer, must be the deal's oracle) and the `deal` PDA.
    /// * `round` - the zero-based round, `0..total_rounds`.
    /// * `up` - whether the service was up for the whole round.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the round is counted; emits `ObservationRecorded`.
    ///
    /// # Errors
    ///
    /// * `DealError::UnauthorizedOracle` - the signer is not the deal's oracle.
    /// * `DealError::DealNotActive` - the window hasn't started.
    /// * `DealError::RoundOutOfRange`, `DealError::RoundNotEnded`, `DealError::ObservationsClosed`,
    ///   `DealError::RoundAlreadyRecorded` - the round can't be recorded now.
    pub fn record_observation(ctx: Context<RecordObservation>, round: u32, up: bool) -> Result<()> {
        instructions::record_observation::handle_record_observation(ctx, round, up)
    }

    /// Settles a deal from its on-chain counters and closes it. Anyone may call it.
    ///
    /// Pays the whole escrow (payment plus guarantee) to the recipient when
    /// `up_checks * 10_000 >= min_uptime_bps * total_rounds`; otherwise to the payer. The `Deal`
    /// account is closed and its rent returns to the payer either way.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `caller` (signer, pays the fee), the `deal` PDA, and the deal's
    ///   `payer` and `recipient` wallets (both writable).
    ///
    /// # Returns
    ///
    /// `Ok(())` once the escrow is paid out and the deal is closed; emits `DealSettled`.
    ///
    /// # Errors
    ///
    /// * `DealError::DealNotActive` - the window never started.
    /// * `DealError::SettleTooEarly` - the window plus `OBSERVATION_GRACE_SECONDS` hasn't passed.
    /// * Anchor constraint errors if `payer`/`recipient` don't match the deal.
    pub fn settle_deal(ctx: Context<SettleDeal>) -> Result<()> {
        instructions::settle_deal::handle_settle_deal(ctx)
    }

    /// Returns the payment of a deal the provider never accepted to its payer and closes it.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `payer` (signer, must be the deal's payer) and the `deal` PDA.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the payment and the rent are back with the payer; emits `DealCancelled`.
    ///
    /// # Errors
    ///
    /// * `DealError::DealAlreadyActive` - the provider has accepted; settle the deal instead.
    /// * Anchor constraint errors if the signer isn't the deal's payer.
    pub fn cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
        instructions::cancel_deal::handle_cancel_deal(ctx)
    }
}
