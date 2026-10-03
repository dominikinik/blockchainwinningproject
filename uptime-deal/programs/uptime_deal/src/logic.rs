//! Pure deal rules with no Solana types, unit-tested below.

use crate::constants::{
    ACCEPT_TIMEOUT_SECONDS, CANCEL_TIMEOUT_SECONDS, MAX_DEAL_DURATION_SECONDS, MIN_DEAL_LAMPORTS, UPTIME_THRESHOLD_PERCENT,
};

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

/// Checks that a deposit (the payer's payment or the recipient's guarantee) is large enough.
///
/// # Arguments
///
/// * `amount_lamports` - the lamports a party wants to lock.
///
/// # Returns
///
/// `true` when `amount_lamports >= MIN_DEAL_LAMPORTS`, otherwise `false`.
pub fn amount_is_valid(amount_lamports: u64) -> bool {
    amount_lamports >= MIN_DEAL_LAMPORTS
}

/// Checks that an uptime window length is allowed.
///
/// # Arguments
///
/// * `duration_seconds` - the window length the payer asked for.
///
/// # Returns
///
/// `true` when `1 <= duration_seconds <= MAX_DEAL_DURATION_SECONDS`, otherwise `false`.
pub fn duration_is_valid(duration_seconds: u64) -> bool {
    (1..=MAX_DEAL_DURATION_SECONDS).contains(&duration_seconds)
}

/// Decides whether the payer may cancel a deal and take the escrow back.
///
/// # Arguments
///
/// * `starts_at` - chain time (unix seconds) when the window started.
/// * `duration_seconds` - window length in seconds (at most `MAX_DEAL_DURATION_SECONDS`).
/// * `now` - the current chain time (unix seconds).
///
/// # Returns
///
/// `true` once `now >= starts_at + duration_seconds + CANCEL_TIMEOUT_SECONDS`, otherwise `false`.
pub fn cancel_allowed(starts_at: i64, duration_seconds: u64, now: i64) -> bool {
    // i128 keeps the sum exact for any stored values.
    now as i128 >= starts_at as i128 + duration_seconds as i128 + CANCEL_TIMEOUT_SECONDS as i128
}

/// The terms a recipient agrees to when it accepts a deal. `accept_deal` compares the terms the
/// recipient signed with the stored ones, so a proposal replaced at the same address can't be accepted
/// by a transaction built for the old one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Terms {
    /// The payer's payment in lamports.
    pub amount_lamports: u64,
    /// The recipient's guarantee in lamports.
    pub guarantee_lamports: u64,
    /// Length of the uptime window in seconds.
    pub duration_seconds: u64,
    /// The oracle's public key bytes.
    pub oracle: [u8; 32],
}

/// Tells whether the terms a recipient signed are exactly the deal's terms.
///
/// # Arguments
///
/// * `stored` - the terms recorded in the deal.
/// * `signed` - the terms the recipient passed to `accept_deal`.
///
/// # Returns
///
/// `true` when every field matches, otherwise `false`.
pub fn terms_match(stored: &Terms, signed: &Terms) -> bool {
    stored == signed
}

/// Computes until when a new proposal can be accepted.
///
/// # Arguments
///
/// * `now` - the chain time (unix seconds) of `create_deal`.
///
/// # Returns
///
/// `now + ACCEPT_TIMEOUT_SECONDS`, saturating at `i64::MAX`.
pub fn accept_deadline(now: i64) -> i64 {
    now.saturating_add(ACCEPT_TIMEOUT_SECONDS)
}

/// Tells whether a proposal can still be accepted.
///
/// # Arguments
///
/// * `accept_deadline` - the deadline stored in the deal.
/// * `now` - the current chain time (unix seconds).
///
/// # Returns
///
/// `true` while `now < accept_deadline`, otherwise `false`.
pub fn accept_open(accept_deadline: i64, now: i64) -> bool {
    now < accept_deadline
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

    #[test]
    fn duration_boundary() {
        assert!(!duration_is_valid(0));
        assert!(duration_is_valid(1));
        assert!(duration_is_valid(MAX_DEAL_DURATION_SECONDS));
        assert!(!duration_is_valid(MAX_DEAL_DURATION_SECONDS + 1));
        assert!(!duration_is_valid(u64::MAX));
    }

    #[test]
    fn cancel_opens_after_window_and_timeout() {
        let end = 1_000 + 60;
        assert!(!cancel_allowed(1_000, 60, 1_000));
        assert!(!cancel_allowed(1_000, 60, end));
        assert!(!cancel_allowed(1_000, 60, end + CANCEL_TIMEOUT_SECONDS - 1));
        assert!(cancel_allowed(1_000, 60, end + CANCEL_TIMEOUT_SECONDS));
        assert!(cancel_allowed(1_000, 60, i64::MAX));
    }

    #[test]
    fn cancel_math_does_not_overflow() {
        assert!(!cancel_allowed(i64::MAX, u64::MAX, i64::MAX));
        assert!(cancel_allowed(i64::MIN, 0, 0));
    }

    fn terms() -> Terms {
        Terms { amount_lamports: 10, guarantee_lamports: 20, duration_seconds: 60, oracle: [7; 32] }
    }

    #[test]
    fn terms_match_only_when_every_field_matches() {
        assert!(terms_match(&terms(), &terms()));
        assert!(!terms_match(&terms(), &Terms { amount_lamports: 11, ..terms() }));
        assert!(!terms_match(&terms(), &Terms { guarantee_lamports: 19, ..terms() }));
        assert!(!terms_match(&terms(), &Terms { duration_seconds: 61, ..terms() }));
        assert!(!terms_match(&terms(), &Terms { oracle: [8; 32], ..terms() }));
    }

    #[test]
    fn accept_deadline_is_one_timeout_after_creation() {
        assert_eq!(accept_deadline(1_000), 1_000 + ACCEPT_TIMEOUT_SECONDS);
        assert_eq!(accept_deadline(i64::MAX - 1), i64::MAX);
    }

    #[test]
    fn accept_closes_at_the_deadline() {
        let deadline = accept_deadline(1_000);
        assert!(accept_open(deadline, 1_000));
        assert!(accept_open(deadline, deadline - 1));
        assert!(!accept_open(deadline, deadline));
        assert!(!accept_open(deadline, i64::MAX));
    }
}
