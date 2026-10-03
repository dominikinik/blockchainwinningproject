//! `uptime_deal`: an uptime agreement between a payer (the customer) and a recipient (the provider).
//!
//! The payer proposes a deal: it locks its payment in a `Deal` account and names the recipient, the
//! guarantee the recipient must lock, the uptime oracle and the window length. The recipient accepts by
//! signing the same terms and locking its guarantee; only then does the uptime window start. When the
//! oracle settles the deal with the measured uptime, the recipient receives both deposits if uptime is
//! strictly above 99%; otherwise the payer does. A proposal can be withdrawn or rejected at any time, and
//! an accepted deal the oracle never settles can be cancelled by either party after a timeout, which
//! returns each deposit to the party that paid it.
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

    /// Proposes a deal and locks the payer's payment in escrow.
    ///
    /// Moves `amount_lamports` from the payer into the new `Deal` PDA, which records the payer, the
    /// recipient, the oracle and the terms. The deal waits for the recipient's `accept_deal`.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `payer` (signer, pays the payment and the rent), `recipient` (must accept
    ///   and is paid when uptime is above 99%), `oracle` (the only key allowed to settle), the `deal`
    ///   PDA to create (seeds `["deal", payer, deal_id]`) and the system program.
    /// * `deal_id` - payer-chosen number that makes the deal address unique per payer.
    /// * `amount_lamports` - the payer's payment; at least `MIN_DEAL_LAMPORTS`.
    /// * `guarantee_lamports` - the guarantee the recipient locks on acceptance; at least
    ///   `MIN_DEAL_LAMPORTS`.
    /// * `duration_seconds` - length of the uptime window, which starts at acceptance; 1 to
    ///   `MAX_DEAL_DURATION_SECONDS`.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the proposal exists and holds the payment; emits `DealCreated`.
    ///
    /// # Errors
    ///
    /// * `DealError::AmountTooSmall` - `amount_lamports` is below `MIN_DEAL_LAMPORTS`.
    /// * `DealError::GuaranteeTooSmall` - `guarantee_lamports` is below `MIN_DEAL_LAMPORTS`.
    /// * `DealError::RecipientIsPayer` - the recipient is the payer.
    /// * `DealError::InvalidDuration` - `duration_seconds` is out of range.
    /// * Anchor/system errors if the deal already exists or the payer cannot pay.
    pub fn create_deal(
        ctx: Context<CreateDeal>,
        deal_id: u64,
        amount_lamports: u64,
        guarantee_lamports: u64,
        duration_seconds: u64,
    ) -> Result<()> {
        instructions::create_deal::handle_create_deal(ctx, deal_id, amount_lamports, guarantee_lamports, duration_seconds)
    }

    /// Accepts a proposed deal, locks the recipient's guarantee and starts the uptime window.
    ///
    /// The recipient passes the terms it agrees to; they must equal the deal's terms, so a proposal
    /// replaced at the same address can't be accepted by a transaction built for the old one.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `recipient` (signer, must be the deal's recipient, pays the guarantee), the
    ///   `deal` PDA and the system program.
    /// * `amount_lamports` - the payer's payment the recipient agrees to.
    /// * `guarantee_lamports` - the guarantee the recipient agrees to lock.
    /// * `duration_seconds` - the window length the recipient agrees to.
    /// * `oracle` - the oracle the recipient agrees to.
    ///
    /// # Returns
    ///
    /// `Ok(())` once the deal is active and holds both deposits; emits `DealAccepted`.
    ///
    /// # Errors
    ///
    /// * `DealError::DealNotProposed` - the deal was already accepted.
    /// * `DealError::AcceptExpired` - `ACCEPT_TIMEOUT_SECONDS` have passed since the proposal.
    /// * `DealError::TermsMismatch` - the given terms differ from the deal's.
    /// * Anchor constraint errors if the signer isn't the deal's recipient; a system error if it
    ///   cannot pay.
    pub fn accept_deal(
        ctx: Context<AcceptDeal>,
        amount_lamports: u64,
        guarantee_lamports: u64,
        duration_seconds: u64,
        oracle: Pubkey,
    ) -> Result<()> {
        let signed = logic::Terms { amount_lamports, guarantee_lamports, duration_seconds, oracle: oracle.to_bytes() };
        instructions::accept_deal::handle_accept_deal(ctx, signed)
    }

    /// Settles an accepted deal from the measured uptime and closes it.
    ///
    /// Pays both deposits to the recipient when `up_seconds / total_seconds` is strictly above 99%;
    /// otherwise to the payer. The `Deal` account is closed and its rent returns to the payer either way.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `oracle` (signer, must be the deal's oracle), the `deal` PDA, and the deal's
    ///   `payer` and `recipient` wallets (both writable).
    /// * `up_seconds` - seconds the application was up during the measured period.
    /// * `total_seconds` - length of the measured period in seconds; must be positive.
    ///
    /// # Returns
    ///
    /// `Ok(())` once both deposits are paid out and the deal is closed; emits `DealSettled`.
    ///
    /// # Errors
    ///
    /// * `DealError::DealNotActive` - the deal hasn't been accepted.
    /// * `DealError::InvalidUptime` - `total_seconds` is 0 or `up_seconds > total_seconds`.
    /// * `DealError::UnauthorizedOracle` - the signer is not the deal's oracle.
    /// * Anchor constraint errors if `payer`/`recipient` don't match the deal.
    pub fn settle_deal(ctx: Context<SettleDeal>, up_seconds: u64, total_seconds: u64) -> Result<()> {
        instructions::settle_deal::handle_settle_deal(ctx, up_seconds, total_seconds)
    }

    /// Returns each deposit to the party that paid it and closes the deal.
    ///
    /// A proposal can be cancelled at any time by the payer (withdraw) or the recipient (reject). An
    /// accepted deal can be cancelled by either of them once its window and `CANCEL_TIMEOUT_SECONDS`
    /// have passed without a settlement.
    ///
    /// # Arguments
    ///
    /// * `ctx` - accounts: `signer` (the payer or the recipient), the `deal` PDA, and the deal's `payer`
    ///   and `recipient` wallets (both writable).
    ///
    /// # Returns
    ///
    /// `Ok(())` once the payment and the rent are back with the payer and any guarantee is back with the
    /// recipient; emits `DealCancelled`.
    ///
    /// # Errors
    ///
    /// * `DealError::NotAParty` - the signer is neither the payer nor the recipient.
    /// * `DealError::CancelTooEarly` - the deal is accepted and the chain time is before
    ///   `starts_at + duration_seconds + CANCEL_TIMEOUT_SECONDS`.
    /// * Anchor constraint errors if `payer`/`recipient` don't match the deal.
    pub fn cancel_deal(ctx: Context<CancelDeal>) -> Result<()> {
        instructions::cancel_deal::handle_cancel_deal(ctx)
    }
}
