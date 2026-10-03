//! Pure deal rules with no Solana types, unit-tested below.

use crate::constants::{MIN_DEAL_LAMPORTS, UPTIME_THRESHOLD_PERCENT};

/// Why an uptime measurement was rejected.
#[derive(Debug, PartialEq, Eq)]
pub enum UptimeError {
    /// `total_seconds` was 0, so there is no ratio to judge.
    EmptyPeriod,
    /// `up_seconds` exceeded `total_seconds`.
    UpExceedsTotal,
}

/// Decides whether a measured uptime earns the recipient the payment.
///
/// Compares `up_seconds / total_seconds` with `UPTIME_THRESHOLD_PERCENT` in exact integer
/// arithmetic (`up * 100 > total * 99`), so there is no rounding at the boundary.
///
/// # Arguments
///
/// * `up_seconds` - seconds the application was up in the period.
/// * `total_seconds` - length of the period in seconds.
///
/// # Returns
///
/// `Ok(true)` when uptime is strictly above 99%, `Ok(false)` when it is 99% or less.
///
/// # Errors
///
/// * `UptimeError::EmptyPeriod` - `total_seconds` is 0.
/// * `UptimeError::UpExceedsTotal` - `up_seconds > total_seconds`.
pub fn uptime_above_threshold(up_seconds: u64, total_seconds: u64) -> Result<bool, UptimeError> {
    if total_seconds == 0 {
        return Err(UptimeError::EmptyPeriod);
    }
    if up_seconds > total_seconds {
        return Err(UptimeError::UpExceedsTotal);
    }
    // u128 keeps the products exact for any u64 inputs.
    Ok(up_seconds as u128 * 100 > total_seconds as u128 * UPTIME_THRESHOLD_PERCENT as u128)
}

/// Checks that an escrow amount is large enough to open a deal.
///
/// # Arguments
///
/// * `amount_lamports` - the lamports the payer wants to lock.
///
/// # Returns
///
/// `true` when `amount_lamports >= MIN_DEAL_LAMPORTS`, otherwise `false`.
pub fn amount_is_valid(amount_lamports: u64) -> bool {
    amount_lamports >= MIN_DEAL_LAMPORTS
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn above_99_percent_pays() {
        assert_eq!(uptime_above_threshold(991, 1000), Ok(true));
        assert_eq!(uptime_above_threshold(1000, 1000), Ok(true));
        assert_eq!(uptime_above_threshold(9_901, 10_000), Ok(true));
    }

    #[test]
    fn exactly_99_percent_does_not_pay() {
        assert_eq!(uptime_above_threshold(99, 100), Ok(false));
        assert_eq!(uptime_above_threshold(990, 1000), Ok(false));
    }

    #[test]
    fn below_99_percent_does_not_pay() {
        assert_eq!(uptime_above_threshold(989, 1000), Ok(false));
        assert_eq!(uptime_above_threshold(0, 1000), Ok(false));
    }

    #[test]
    fn short_periods_need_full_uptime() {
        // With fewer than 100 seconds, any downtime drops uptime to 99% or below.
        assert_eq!(uptime_above_threshold(1, 1), Ok(true));
        assert_eq!(uptime_above_threshold(98, 99), Ok(false));
    }

    #[test]
    fn huge_values_do_not_overflow() {
        assert_eq!(uptime_above_threshold(u64::MAX, u64::MAX), Ok(true));
        assert_eq!(uptime_above_threshold(u64::MAX / 100 * 99, u64::MAX), Ok(false));
    }

    #[test]
    fn rejects_invalid_measurements() {
        assert_eq!(uptime_above_threshold(0, 0), Err(UptimeError::EmptyPeriod));
        assert_eq!(uptime_above_threshold(11, 10), Err(UptimeError::UpExceedsTotal));
    }

    #[test]
    fn amount_boundary() {
        assert!(!amount_is_valid(0));
        assert!(!amount_is_valid(MIN_DEAL_LAMPORTS - 1));
        assert!(amount_is_valid(MIN_DEAL_LAMPORTS));
        assert!(amount_is_valid(u64::MAX));
    }
}
