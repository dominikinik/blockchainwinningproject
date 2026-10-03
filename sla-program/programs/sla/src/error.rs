use anchor_lang::prelude::*;

/// Error codes are part of the client contract (see SPEC.md). Append new variants at the end;
/// never reorder, because clients match on the numeric codes.
#[error_code]
pub enum SlaError {
    // Config and monitor registry
    #[msg("Signer is not the config admin")]
    Unauthorized,
    #[msg("Config parameters are out of range")]
    InvalidConfig,
    #[msg("Monitor name is empty or longer than 32 bytes")]
    InvalidMonitorName,

    // create_sla
    #[msg("SLA parameters are invalid")]
    InvalidParams,
    #[msg("SLA name is empty or longer than 64 bytes")]
    InvalidSlaName,
    #[msg("Endpoint is empty, longer than 200 bytes, or not https://")]
    InvalidEndpoint,
    #[msg("Escrow must be greater than zero")]
    ZeroEscrow,
    #[msg("Required uptime must be between 1 and 10000 basis points")]
    InvalidUptimeTarget,
    #[msg("Duration must be positive, at most 90 days, and at most 2160 windows")]
    InvalidDuration,
    #[msg("Check interval must be at least 10s and fit at most 256 checks in a window")]
    InvalidCheckInterval,
    #[msg("Timeout must be 100..=30000 ms and shorter than the check interval")]
    InvalidTimeout,
    #[msg("Provider must differ from the customer")]
    ProviderIsCustomer,
    #[msg("Monitor list must have 1..=max_monitors_per_sla entries")]
    InvalidMonitorCount,
    #[msg("Monitor list contains a duplicate")]
    DuplicateMonitor,
    #[msg("Consensus must be between 1 and the number of monitors")]
    InvalidConsensus,
    #[msg("Remaining accounts do not match the monitor list")]
    MonitorAccountMismatch,
    #[msg("Monitor is not active")]
    MonitorInactive,

    // submit_report
    #[msg("Signer is not assigned to this SLA")]
    NotAssignedMonitor,
    #[msg("Window index is outside the SLA")]
    InvalidWindowIndex,
    #[msg("Window has not ended yet")]
    WindowNotEnded,
    #[msg("Report deadline for this window has passed")]
    ReportDeadlinePassed,
    #[msg("Monitor already reported this window")]
    DuplicateReport,
    #[msg("Bitmap marks a slot up that was not checked, or a slot past the window's check count")]
    InvalidBitmap,

    // finalize_window
    #[msg("Window must be finalized in order")]
    WindowOutOfOrder,
    #[msg("Report deadline for this window has not passed yet")]
    ReportDeadlineNotPassed,
    #[msg("Window report payer does not match")]
    PayerMismatch,

    // settle
    #[msg("SLA cannot be settled yet")]
    NotYetSettleable,
    #[msg("Not every window is finalized")]
    WindowsNotFinalized,
    #[msg("SLA is already settled")]
    AlreadySettled,

    // shared
    #[msg("Arithmetic overflow")]
    MathOverflow,
}
