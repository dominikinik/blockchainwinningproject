use anchor_lang::prelude::*;

/// One uptime-conditioned payment. The account itself holds the escrowed lamports on top of
/// its rent-exempt reserve until `settle_deal` pays them out and closes it.
#[account]
#[derive(InitSpace)]
pub struct Deal {
    /// Funded the escrow; gets it back when uptime is 99% or less, and always gets the rent.
    pub payer: Pubkey,
    /// Receives the escrow when uptime is above 99%.
    pub recipient: Pubkey,
    /// The only key allowed to report uptime and settle.
    pub oracle: Pubkey,
    /// Payer-chosen id, part of the PDA seeds.
    pub deal_id: u64,
    /// Escrowed lamports.
    pub amount_lamports: u64,
    pub bump: u8,
}
