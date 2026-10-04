use anchor_lang::prelude::*;

/// Emitted by `create_deal` once the payer's payment is locked and the proposal waits for the recipient.
#[event]
pub struct DealCreated {
    pub deal: Pubkey,
    pub payer: Pubkey,
    pub recipient: Pubkey,
    pub oracle: Pubkey,
    pub amount_lamports: u64,
    pub guarantee_lamports: u64,
    pub duration_seconds: u64,
    pub accept_deadline: i64,
}

/// Emitted by `accept_deal` once the recipient's guarantee is locked and the uptime window starts.
#[event]
pub struct DealAccepted {
    pub deal: Pubkey,
    pub recipient: Pubkey,
    pub guarantee_lamports: u64,
    pub starts_at: i64,
    pub duration_seconds: u64,
}

/// Emitted by `settle_deal`; `paid_to_recipient` is false when the payer received both deposits.
/// New fields go at the end: off-chain readers decode the prefix.
#[event]
pub struct DealSettled {
    pub deal: Pubkey,
    pub up_seconds: u64,
    pub total_seconds: u64,
    pub paid_to_recipient: bool,
    pub amount_lamports: u64,
    pub guarantee_lamports: u64,
}

/// Emitted by `cancel_deal` once every deposit is back with the party that paid it.
/// New fields go at the end: off-chain readers decode the prefix.
#[event]
pub struct DealCancelled {
    pub deal: Pubkey,
    pub payer: Pubkey,
    pub amount_lamports: u64,
    /// The payer (withdrawing a proposal or reclaiming a stuck deal) or the recipient (rejecting a
    /// proposal or reclaiming a stuck deal).
    pub cancelled_by: Pubkey,
    /// The guarantee returned to the recipient; 0 for a proposal, which holds no guarantee.
    pub guarantee_refunded_lamports: u64,
}
