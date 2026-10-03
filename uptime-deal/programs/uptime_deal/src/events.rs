use anchor_lang::prelude::*;

/// Emitted by `create_deal` once the escrow is locked.
#[event]
pub struct DealCreated {
    pub deal: Pubkey,
    pub payer: Pubkey,
    pub recipient: Pubkey,
    pub oracle: Pubkey,
    pub amount_lamports: u64,
}

/// Emitted by `settle_deal`; `paid_to_recipient` is false when the payer was refunded.
#[event]
pub struct DealSettled {
    pub deal: Pubkey,
    pub up_seconds: u64,
    pub total_seconds: u64,
    pub paid_to_recipient: bool,
    pub amount_lamports: u64,
}
