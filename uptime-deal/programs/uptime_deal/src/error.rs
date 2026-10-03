use anchor_lang::prelude::*;

/// Errors of the `uptime_deal` program. The order fixes the 6000+ codes, so append only.
#[error_code]
pub enum DealError {
    #[msg("Deal amount is below the minimum")]
    AmountTooSmall,
    #[msg("Recipient must differ from the payer")]
    RecipientIsPayer,
    #[msg("Signer is not the deal's oracle")]
    UnauthorizedOracle,
    #[msg("Uptime needs total_seconds > 0 and up_seconds <= total_seconds")]
    InvalidUptime,
}
