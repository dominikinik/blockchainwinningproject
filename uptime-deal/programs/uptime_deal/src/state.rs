use anchor_lang::prelude::*;

/// Where a deal stands. A settled or cancelled deal is closed, so it needs no status of its own.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq, InitSpace)]
pub enum DealStatus {
    /// The payer proposed the deal and paid; the recipient hasn't accepted yet, so no window runs.
    Proposed,
    /// The recipient accepted and paid its guarantee; the uptime window runs from `starts_at`.
    Active,
}

/// One uptime-conditioned agreement. The account itself holds both deposits (the payer's payment and
/// the recipient's guarantee, once accepted) on top of its rent-exempt reserve until the deal is
/// settled or cancelled and closed.
#[account]
#[derive(InitSpace)]
pub struct Deal {
    /// Proposed the deal and paid `amount_lamports`; receives both deposits when uptime is 99% or
    /// less, and always gets the rent.
    pub payer: Pubkey,
    /// The provider: accepts the deal, pays `guarantee_lamports`, and receives both deposits when
    /// uptime is above 99%.
    pub recipient: Pubkey,
    /// The only key allowed to report uptime and settle.
    pub oracle: Pubkey,
    /// Payer-chosen id, part of the PDA seeds.
    pub deal_id: u64,
    /// The payer's payment, locked at creation.
    pub amount_lamports: u64,
    /// The recipient's guarantee, locked at acceptance.
    pub guarantee_lamports: u64,
    /// Length of the uptime window in seconds; it ends (exclusive) at `starts_at + duration_seconds`.
    pub duration_seconds: u64,
    /// Chain time (unix seconds) from which the proposal can no longer be accepted.
    pub accept_deadline: i64,
    /// Chain time (unix seconds) when the recipient accepted; the uptime window starts here.
    /// 0 while the deal is `Proposed`.
    pub starts_at: i64,
    pub status: DealStatus,
    pub bump: u8,
}
