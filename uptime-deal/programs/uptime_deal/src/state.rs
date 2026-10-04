use anchor_lang::prelude::*;

/// Where a deal stands. A settled or cancelled deal is closed, so it has no status.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq, InitSpace)]
pub enum DealStatus {
    /// Created with a provider guarantee that the recipient hasn't locked yet. The window hasn't
    /// started; only the payer can act (`cancel_deal`), or the recipient (`accept_deal`).
    AwaitingProvider,
    /// Both sides are locked and the window runs from `starts_at`.
    Active,
}

/// One uptime-conditioned agreement, and the authoritative SLA state it is settled from.
///
/// The account holds the customer payment and the provider guarantee on top of its rent-exempt
/// reserve until `settle_deal` pays them out and closes it. The monitoring counters live here, so
/// settlement needs nothing from off chain.
#[account]
#[derive(Debug)]
pub struct Deal {
    /// The customer: funds `amount_lamports`, pays the rent, and gets the whole escrow on a breach.
    pub payer: Pubkey,
    /// The provider: locks `provider_stake_lamports` and gets the whole escrow when the SLA is met.
    pub recipient: Pubkey,
    /// The only key allowed to record observations.
    pub oracle: Pubkey,
    /// Payer-chosen id, part of the PDA seeds.
    pub deal_id: u64,
    /// The customer's payment, in lamports.
    pub amount_lamports: u64,
    /// The provider's performance guarantee, in lamports (0 means none, and the deal starts at once).
    pub provider_stake_lamports: u64,
    /// Last chain time when the provider may accept; informational after activation.
    pub accept_deadline: i64,
    pub status: DealStatus,
    /// Chain time (unix seconds) when the deal became active; the window starts here. 0 until then.
    pub starts_at: i64,
    /// Length of the window in seconds; it ends (exclusive) at `starts_at + duration_seconds`.
    pub duration_seconds: u64,
    /// Length of one monitoring round in seconds. Round `r` covers
    /// `[starts_at + r * interval, starts_at + (r + 1) * interval)`.
    pub check_interval_seconds: u64,
    /// Required uptime in basis points; the provider wins when `up_checks / total_rounds` reaches it.
    pub min_uptime_bps: u16,
    /// `duration_seconds / check_interval_seconds`. Rounds with no observation count as down.
    pub total_rounds: u32,
    /// Rounds observed UP.
    pub up_checks: u32,
    /// Rounds observed DOWN.
    pub down_checks: u32,
    pub bump: u8,
    /// One bit per round (bit `r % 8` of byte `r / 8`), set once that round is recorded, so no
    /// round is ever counted twice.
    pub recorded: Vec<u8>,
}

impl Deal {
    /// Bytes of a `Deal` before its bitmap, including the 8-byte discriminator and the bitmap's
    /// 4-byte length prefix.
    pub const FIXED_SPACE: usize = 8 + 32 * 3 + 8 * 4 + 1 + 8 * 3 + 2 + 4 * 3 + 1 + 4;

    /// Computes the account size of a deal.
    ///
    /// # Arguments
    ///
    /// * `total_rounds` - the number of monitoring rounds in the window.
    ///
    /// # Returns
    ///
    /// The bytes to allocate: the fixed fields plus one bit per round, rounded up to whole bytes.
    pub fn space(total_rounds: u32) -> usize {
        Self::FIXED_SPACE + (total_rounds as usize).div_ceil(8)
    }
}
