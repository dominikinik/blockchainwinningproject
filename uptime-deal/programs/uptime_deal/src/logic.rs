//! Pure deal rules with no Solana types, unit-tested below.

use crate::constants::{BPS_DENOMINATOR, MAX_DEAL_DURATION_SECONDS, MAX_ROUNDS, MIN_DEAL_LAMPORTS, OBSERVATION_GRACE_SECONDS};

/// Decides whether the recorded uptime meets the SLA.
///
/// Compares `up_checks / total_rounds` with `min_uptime_bps / 10,000` by cross-multiplication in
/// exact integer arithmetic (`up * 10_000 >= min_bps * total`), so there is no rounding at the
/// boundary. Rounds that were never observed are in `total_rounds` but not in `up_checks`, so they
/// count as down.
///
/// # Arguments
///
/// * `up_checks` - rounds observed UP.
/// * `total_rounds` - every round of the window, observed or not.
/// * `min_uptime_bps` - the required uptime in basis points.
///
/// # Returns
///
/// `true` when uptime is at or above the threshold (provider wins), `false` otherwise or when
/// there are no rounds at all.
pub fn sla_met(up_checks: u32, total_rounds: u32, min_uptime_bps: u16) -> bool {
    if total_rounds == 0 {
        return false;
    }
    // u64 keeps both products exact: at most 2^32 * 10^4.
    up_checks as u64 * BPS_DENOMINATOR >= min_uptime_bps as u64 * total_rounds as u64
}

/// Checks that a customer payment is large enough to open a deal.
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

/// Checks that an uptime threshold is a meaningful percentage.
///
/// # Arguments
///
/// * `min_uptime_bps` - the required uptime in basis points.
///
/// # Returns
///
/// `true` when `1 <= min_uptime_bps <= 10_000`, otherwise `false`.
pub fn threshold_is_valid(min_uptime_bps: u16) -> bool {
    (1..=BPS_DENOMINATOR).contains(&(min_uptime_bps as u64))
}

/// Splits a window into monitoring rounds.
///
/// # Arguments
///
/// * `duration_seconds` - the window length.
/// * `check_interval_seconds` - the length of one round.
///
/// # Returns
///
/// `Some(duration / interval)` when the interval is positive, divides the duration exactly and
/// gives 1 to `MAX_ROUNDS` rounds; `None` otherwise.
pub fn total_rounds(duration_seconds: u64, check_interval_seconds: u64) -> Option<u32> {
    if check_interval_seconds == 0 || duration_seconds % check_interval_seconds != 0 {
        return None;
    }
    let rounds = duration_seconds / check_interval_seconds;
    if rounds == 0 || rounds > MAX_ROUNDS {
        return None;
    }
    Some(rounds as u32)
}

/// Decides whether a round is over on the chain clock, so that an observation of it is a report of
/// the past rather than a promise about the future.
///
/// # Arguments
///
/// * `starts_at` - chain time (unix seconds) when the window started.
/// * `check_interval_seconds` - the length of one round.
/// * `round` - the zero-based round.
/// * `now` - the current chain time (unix seconds).
///
/// # Returns
///
/// `true` once `now >= starts_at + (round + 1) * check_interval_seconds`.
pub fn round_ended(starts_at: i64, check_interval_seconds: u64, round: u32, now: i64) -> bool {
    // i128 keeps the sum exact for any stored values.
    now as i128 >= starts_at as i128 + (round as i128 + 1) * check_interval_seconds as i128
}

/// Decides whether the deal still accepts observations.
///
/// # Arguments
///
/// * `starts_at` - chain time (unix seconds) when the window started.
/// * `duration_seconds` - window length in seconds.
/// * `now` - the current chain time (unix seconds).
///
/// # Returns
///
/// `true` while `now < starts_at + duration_seconds + OBSERVATION_GRACE_SECONDS`.
pub fn observations_open(starts_at: i64, duration_seconds: u64, now: i64) -> bool {
    !settle_allowed(starts_at, duration_seconds, now)
}

/// Decides whether the deal can be settled: its window and the observation grace are over.
///
/// # Arguments
///
/// * `starts_at` - chain time (unix seconds) when the window started.
/// * `duration_seconds` - window length in seconds.
/// * `now` - the current chain time (unix seconds).
///
/// # Returns
///
/// `true` once `now >= starts_at + duration_seconds + OBSERVATION_GRACE_SECONDS`.
pub fn settle_allowed(starts_at: i64, duration_seconds: u64, now: i64) -> bool {
    now as i128 >= starts_at as i128 + duration_seconds as i128 + OBSERVATION_GRACE_SECONDS as i128
}

/// Tells whether a round's bit is set.
///
/// # Arguments
///
/// * `recorded` - the deal's bitmap.
/// * `round` - the zero-based round.
///
/// # Returns
///
/// `true` if the round was recorded; `false` if not, or if it lies beyond the bitmap.
pub fn is_recorded(recorded: &[u8], round: u32) -> bool {
    recorded.get(round as usize / 8).is_some_and(|byte| byte & (1 << (round % 8)) != 0)
}

/// Sets a round's bit.
///
/// # Arguments
///
/// * `recorded` - the deal's bitmap.
/// * `round` - the zero-based round; must lie inside the bitmap.
///
/// # Returns
///
/// `true` if the bit was newly set, `false` if it was already set or lies beyond the bitmap.
pub fn mark_recorded(recorded: &mut [u8], round: u32) -> bool {
    match recorded.get_mut(round as usize / 8) {
        Some(byte) if *byte & (1 << (round % 8)) == 0 => {
            *byte |= 1 << (round % 8);
            true
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sla_met_at_or_above_threshold() {
        assert!(sla_met(99, 100, 9_900));
        assert!(sla_met(100, 100, 9_900));
        assert!(sla_met(9_901, 10_000, 9_900));
        assert!(sla_met(1, 1, 10_000));
    }

    #[test]
    fn sla_breached_below_threshold() {
        assert!(!sla_met(98, 100, 9_900));
        assert!(!sla_met(9_899, 10_000, 9_900));
        assert!(!sla_met(0, 1_000, 1));
        assert!(!sla_met(99, 100, 10_000));
    }

    #[test]
    fn short_windows_round_against_the_provider() {
        // 9 of 10 rounds is 90%: enough for 9,000 bps, not for 9,001.
        assert!(sla_met(9, 10, 9_000));
        assert!(!sla_met(9, 10, 9_001));
    }

    #[test]
    fn no_rounds_never_meets_the_sla() {
        assert!(!sla_met(0, 0, 1));
    }

    #[test]
    fn huge_values_do_not_overflow() {
        assert!(sla_met(u32::MAX, u32::MAX, 10_000));
        assert!(!sla_met(u32::MAX - 1, u32::MAX, 10_000));
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
    fn threshold_boundary() {
        assert!(!threshold_is_valid(0));
        assert!(threshold_is_valid(1));
        assert!(threshold_is_valid(10_000));
        assert!(!threshold_is_valid(10_001));
        assert!(!threshold_is_valid(u16::MAX));
    }

    #[test]
    fn rounds_split_the_window_exactly() {
        assert_eq!(total_rounds(60, 1), Some(60));
        assert_eq!(total_rounds(60, 10), Some(6));
        assert_eq!(total_rounds(60, 60), Some(1));
        assert_eq!(total_rounds(MAX_ROUNDS, 1), Some(MAX_ROUNDS as u32));
    }

    #[test]
    fn rejects_invalid_round_splits() {
        assert_eq!(total_rounds(60, 0), None);
        assert_eq!(total_rounds(60, 7), None, "must divide evenly");
        assert_eq!(total_rounds(60, 120), None, "longer than the window");
        assert_eq!(total_rounds(MAX_ROUNDS + 1, 1), None, "too many rounds");
        assert_eq!(total_rounds(0, 1), None);
    }

    #[test]
    fn round_ends_after_its_interval() {
        assert!(!round_ended(1_000, 5, 0, 1_004));
        assert!(round_ended(1_000, 5, 0, 1_005));
        assert!(!round_ended(1_000, 5, 3, 1_019));
        assert!(round_ended(1_000, 5, 3, 1_020));
        assert!(!round_ended(i64::MAX, u64::MAX, u32::MAX, i64::MAX));
    }

    #[test]
    fn observations_close_when_settlement_opens() {
        let end = 1_000 + 60;
        assert!(observations_open(1_000, 60, end));
        assert!(observations_open(1_000, 60, end + OBSERVATION_GRACE_SECONDS - 1));
        assert!(!settle_allowed(1_000, 60, end + OBSERVATION_GRACE_SECONDS - 1));
        assert!(!observations_open(1_000, 60, end + OBSERVATION_GRACE_SECONDS));
        assert!(settle_allowed(1_000, 60, end + OBSERVATION_GRACE_SECONDS));
        assert!(!settle_allowed(i64::MAX, u64::MAX, i64::MAX));
    }

    #[test]
    fn bitmap_marks_each_round_once() {
        let mut bits = vec![0u8; 2];
        assert!(!is_recorded(&bits, 9));
        assert!(mark_recorded(&mut bits, 9));
        assert!(is_recorded(&bits, 9));
        assert!(!mark_recorded(&mut bits, 9), "second mark is refused");
        assert!(!is_recorded(&bits, 8));
        assert_eq!(bits, vec![0, 0b10]);
    }

    #[test]
    fn bitmap_ignores_rounds_past_its_end() {
        let mut bits = vec![0u8; 1];
        assert!(!is_recorded(&bits, 8));
        assert!(!mark_recorded(&mut bits, 8));
        assert_eq!(bits, vec![0]);
    }
}
