use anchor_lang::prelude::*;

/// Emitted by `create_deal` once the customer payment is locked.
#[event]
pub struct DealCreated {
    pub deal: Pubkey,
    pub payer: Pubkey,
    pub recipient: Pubkey,
    pub oracle: Pubkey,
    pub amount_lamports: u64,
    pub provider_stake_lamports: u64,
    pub duration_seconds: u64,
    pub check_interval_seconds: u64,
    pub min_uptime_bps: u16,
    pub total_rounds: u32,
}

/// Emitted when the window starts: by `create_deal` without a guarantee, or by `accept_deal`.
#[event]
pub struct DealStarted {
    pub deal: Pubkey,
    pub starts_at: i64,
    pub ends_at: i64,
}

/// Emitted by `record_observation` with the counters after the round was added.
#[event]
pub struct ObservationRecorded {
    pub deal: Pubkey,
    pub round: u32,
    pub up: bool,
    pub up_checks: u32,
    pub down_checks: u32,
}

/// Emitted by `settle_deal`; `paid_to_recipient` is false when the customer took the escrow.
#[event]
pub struct DealSettled {
    pub deal: Pubkey,
    pub up_checks: u32,
    pub down_checks: u32,
    pub total_rounds: u32,
    pub min_uptime_bps: u16,
    pub paid_to_recipient: bool,
    /// The whole escrow that moved to the winner: payment plus guarantee.
    pub payout_lamports: u64,
}

/// Emitted by `cancel_deal` once the payment and the rent are back with the payer.
#[event]
pub struct DealCancelled {
    pub deal: Pubkey,
    pub payer: Pubkey,
    pub amount_lamports: u64,
}
