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
    /// No longer returned: `settle_deal` takes no uptime figures since the counters moved on chain.
    #[msg("Uptime needs total_seconds > 0 and up_seconds <= total_seconds")]
    InvalidUptime,
    #[msg("Deal duration must be between 1 second and MAX_DEAL_DURATION_SECONDS")]
    InvalidDuration,
    /// No longer returned: an active deal is never cancelled, anyone can settle it instead.
    #[msg("The deal can be cancelled only CANCEL_TIMEOUT_SECONDS after its window ends")]
    CancelTooEarly,
    #[msg("Check interval must divide the duration into 1 to MAX_ROUNDS rounds")]
    InvalidCheckInterval,
    #[msg("Minimum uptime must be between 1 and 10,000 basis points")]
    InvalidThreshold,
    #[msg("The deal is waiting for the provider's guarantee")]
    DealNotActive,
    #[msg("The provider has already accepted the deal")]
    DealAlreadyActive,
    #[msg("Round is outside the deal's window")]
    RoundOutOfRange,
    #[msg("Round has not ended yet on the chain clock")]
    RoundNotEnded,
    #[msg("Round was already recorded")]
    RoundAlreadyRecorded,
    #[msg("Observations closed OBSERVATION_GRACE_SECONDS after the window ended")]
    ObservationsClosed,
    #[msg("The deal can be settled only OBSERVATION_GRACE_SECONDS after its window ends")]
    SettleTooEarly,
    #[msg("Arithmetic overflow")]
    Overflow,
}
