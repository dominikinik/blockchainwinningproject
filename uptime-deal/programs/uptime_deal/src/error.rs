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
    #[msg("Deal duration must be between 1 second and MAX_DEAL_DURATION_SECONDS")]
    InvalidDuration,
    #[msg("The deal can be cancelled only CANCEL_TIMEOUT_SECONDS after its window ends")]
    CancelTooEarly,
    #[msg("Deal guarantee is below the minimum")]
    GuaranteeTooSmall,
    #[msg("The deal is not a proposal waiting for acceptance")]
    DealNotProposed,
    #[msg("The deal has not been accepted yet")]
    DealNotActive,
    #[msg("The proposal can no longer be accepted")]
    AcceptExpired,
    #[msg("The accepted terms differ from the deal's terms")]
    TermsMismatch,
    #[msg("Only the deal's payer or recipient can cancel it")]
    NotAParty,
}
