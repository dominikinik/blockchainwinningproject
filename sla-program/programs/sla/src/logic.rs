//! Pure SLA logic with no Solana types: parameter limits, window and slot math, bitmap
//! consensus, and the payout choice. Handlers call these; unit tests drive them directly.

use crate::{constants::*, error::SlaError};

/// `initialize_config` limits (SPEC.md).
pub fn validate_config(
    window_secs: u32,
    report_grace_secs: u32,
    max_monitors_per_sla: u8,
) -> Result<(), SlaError> {
    let ok = (10..=86_400).contains(&window_secs)
        && (1..=86_400).contains(&report_grace_secs)
        && (1..=MAX_MONITORS_PER_SLA).contains(&max_monitors_per_sla);
    if ok {
        Ok(())
    } else {
        Err(SlaError::InvalidConfig)
    }
}

pub fn validate_monitor_name(name: &str) -> Result<(), SlaError> {
    if name.is_empty() || name.len() > MAX_MONITOR_NAME_LEN as usize {
        return Err(SlaError::InvalidMonitorName);
    }
    Ok(())
}

/// The scalar `create_sla` parameters, after the monitor checks. Order follows SPEC.md.
pub struct SlaTerms<'a> {
    pub monitor_count: usize,
    pub consensus_required: u8,
    pub name: &'a str,
    pub endpoint: &'a str,
    pub escrow_lamports: u64,
    pub required_uptime_bps: u16,
    pub duration_secs: u32,
    pub check_interval_secs: u32,
    pub timeout_ms: u32,
    /// The config's `window_secs`.
    pub window_secs: u32,
}

/// Validates the `create_sla` terms and returns `total_windows`.
pub fn validate_sla_terms(t: &SlaTerms) -> Result<u32, SlaError> {
    if t.consensus_required == 0 || t.consensus_required as usize > t.monitor_count {
        return Err(SlaError::InvalidConsensus);
    }
    if t.name.is_empty() || t.name.len() > MAX_SLA_NAME_LEN as usize {
        return Err(SlaError::InvalidSlaName);
    }
    if t.endpoint.len() > MAX_ENDPOINT_LEN as usize || !t.endpoint.starts_with("https://") {
        return Err(SlaError::InvalidEndpoint);
    }
    if t.escrow_lamports == 0 {
        return Err(SlaError::ZeroEscrow);
    }
    if t.required_uptime_bps == 0 || t.required_uptime_bps > MAX_UPTIME_BPS {
        return Err(SlaError::InvalidUptimeTarget);
    }
    let total_windows = window_count(t.duration_secs, t.window_secs);
    if t.duration_secs == 0 || t.duration_secs > MAX_SLA_DURATION_SECS || total_windows > MAX_WINDOWS
    {
        return Err(SlaError::InvalidDuration);
    }
    if t.check_interval_secs < MIN_CHECK_INTERVAL_SECS
        || t.window_secs.div_ceil(t.check_interval_secs) > MAX_CHECKS_PER_WINDOW
    {
        return Err(SlaError::InvalidCheckInterval);
    }
    if t.timeout_ms < MIN_TIMEOUT_MS
        || t.timeout_ms > MAX_TIMEOUT_MS
        || t.timeout_ms as u64 >= t.check_interval_secs as u64 * 1_000
    {
        return Err(SlaError::InvalidTimeout);
    }
    Ok(total_windows)
}

/// `ceil(duration_secs / window_secs)`; 0 when `window_secs` is 0.
pub fn window_count(duration_secs: u32, window_secs: u32) -> u32 {
    if window_secs == 0 {
        return 0;
    }
    duration_secs.div_ceil(window_secs)
}

/// The SLA schedule fields that window math needs.
#[derive(Clone, Copy, Debug)]
pub struct Schedule {
    pub start_ts: i64,
    pub end_ts: i64,
    pub window_secs: u32,
    pub report_grace_secs: u32,
    pub check_interval_secs: u32,
    pub total_windows: u32,
}

impl Schedule {
    /// `[start_i, end_i)` of window `index`, or `InvalidWindowIndex`.
    pub fn window_bounds(&self, index: u32) -> Result<(i64, i64), SlaError> {
        if index >= self.total_windows {
            return Err(SlaError::InvalidWindowIndex);
        }
        let start = (index as i64)
            .checked_mul(self.window_secs as i64)
            .and_then(|o| o.checked_add(self.start_ts))
            .ok_or(SlaError::MathOverflow)?;
        let end = start
            .checked_add(self.window_secs as i64)
            .ok_or(SlaError::MathOverflow)?
            .min(self.end_ts);
        Ok((start, end))
    }

    /// `end_i + G`: reports are accepted before it, finalization is allowed from it on.
    pub fn report_deadline(&self, index: u32) -> Result<i64, SlaError> {
        let (_, end) = self.window_bounds(index)?;
        end.checked_add(self.report_grace_secs as i64)
            .ok_or(SlaError::MathOverflow)
    }

    /// Check slots in window `index`: `ceil((end_i − start_i) / I)`.
    pub fn slot_count(&self, index: u32) -> Result<u32, SlaError> {
        let (start, end) = self.window_bounds(index)?;
        if self.check_interval_secs == 0 || end < start {
            return Err(SlaError::MathOverflow);
        }
        Ok(((end - start) as u64).div_ceil(self.check_interval_secs as u64) as u32)
    }

    /// Checks `now` against the reporting period of window `index`.
    pub fn check_report_time(&self, index: u32, now: i64) -> Result<(), SlaError> {
        let (_, end) = self.window_bounds(index)?;
        if now < end {
            return Err(SlaError::WindowNotEnded);
        }
        if now >= self.report_deadline(index)? {
            return Err(SlaError::ReportDeadlinePassed);
        }
        Ok(())
    }
}

pub fn bit(bitmap: &[u8; 32], j: u32) -> bool {
    bitmap[(j / 8) as usize] >> (j % 8) & 1 == 1
}

/// `up ⊆ checked`, and no bit at or above `slots` is set in either bitmap.
pub fn validate_bitmaps(checked: &[u8; 32], up: &[u8; 32], slots: u32) -> Result<(), SlaError> {
    if checked.iter().zip(up).any(|(c, u)| u & !c != 0) {
        return Err(SlaError::InvalidBitmap);
    }
    if (slots..MAX_CHECKS_PER_WINDOW).any(|j| bit(checked, j) || bit(up, j)) {
        return Err(SlaError::InvalidBitmap);
    }
    Ok(())
}

/// One monitor's submitted bitmaps, by position in `Sla::monitors`.
pub type Ballot = ([u8; 32], [u8; 32]);

/// Per-monitor agreement stats from one window.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Agreement {
    pub slots_voted: u64,
    pub slots_agreed: u64,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WindowTally {
    pub up: u16,
    pub counted: u16,
    /// Indexed like the ballots.
    pub agreement: Vec<Agreement>,
}

/// Per-slot `k`-of-`n` consensus (D3) over `slots` check slots. `None` is a monitor that did
/// not report. A slot is UP with `k` up votes, else DOWN with `k` votes, else not counted.
pub fn tally_window(ballots: &[Option<Ballot>], slots: u32, k: u8) -> WindowTally {
    let k = k as u32;
    let mut tally = WindowTally {
        agreement: vec![Agreement::default(); ballots.len()],
        ..Default::default()
    };
    for j in 0..slots.min(MAX_CHECKS_PER_WINDOW) {
        let mut votes = 0u32;
        let mut ups = 0u32;
        for (checked, up) in ballots.iter().flatten() {
            if bit(checked, j) {
                votes += 1;
                if bit(up, j) {
                    ups += 1;
                }
            }
        }
        let outcome = if k > 0 && ups >= k {
            Some(true)
        } else if k > 0 && votes >= k {
            Some(false)
        } else {
            None
        };
        if let Some(is_up) = outcome {
            tally.counted += 1;
            if is_up {
                tally.up += 1;
            }
        }
        for (stats, ballot) in tally.agreement.iter_mut().zip(ballots) {
            let Some((checked, up)) = ballot else { continue };
            if !bit(checked, j) {
                continue;
            }
            stats.slots_voted += 1;
            if outcome == Some(bit(up, j)) {
                stats.slots_agreed += 1;
            }
        }
    }
    tally
}

/// D4: the provider is paid iff at least one check counted and
/// `up_checks · 10_000 ≥ required_uptime_bps · counted_checks`.
pub fn provider_is_paid(up_checks: u64, counted_checks: u64, required_uptime_bps: u16) -> bool {
    counted_checks > 0
        && up_checks as u128 * MAX_UPTIME_BPS as u128
            >= required_uptime_bps as u128 * counted_checks as u128
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bits(slots: &[u32]) -> [u8; 32] {
        let mut b = [0u8; 32];
        for &j in slots {
            b[(j / 8) as usize] |= 1 << (j % 8);
        }
        b
    }

    fn all(n: u32) -> [u8; 32] {
        bits(&(0..n).collect::<Vec<_>>())
    }

    fn terms() -> SlaTerms<'static> {
        SlaTerms {
            monitor_count: 3,
            consensus_required: 2,
            name: "api",
            endpoint: "https://example.com/health",
            escrow_lamports: 1_000,
            required_uptime_bps: 9_990,
            duration_secs: 7_200,
            check_interval_secs: 60,
            timeout_ms: 2_000,
            window_secs: 3_600,
        }
    }

    fn schedule() -> Schedule {
        // 2.5 windows of 100s, 10s checks: the last window is 50s, 5 slots.
        Schedule {
            start_ts: 1_000,
            end_ts: 1_250,
            window_secs: 100,
            report_grace_secs: 30,
            check_interval_secs: 10,
            total_windows: 3,
        }
    }

    #[test]
    fn config_limits() {
        assert!(validate_config(10, 1, 1).is_ok());
        assert!(validate_config(86_400, 86_400, 5).is_ok());
        for (w, g, m) in [(9, 1, 1), (86_401, 1, 1), (10, 0, 1), (10, 86_401, 1), (10, 1, 0), (10, 1, 6)] {
            assert!(matches!(validate_config(w, g, m), Err(SlaError::InvalidConfig)), "{w} {g} {m}");
        }
    }

    #[test]
    fn monitor_name_limits() {
        assert!(validate_monitor_name("a").is_ok());
        assert!(validate_monitor_name(&"a".repeat(32)).is_ok());
        assert!(matches!(validate_monitor_name(""), Err(SlaError::InvalidMonitorName)));
        assert!(matches!(validate_monitor_name(&"a".repeat(33)), Err(SlaError::InvalidMonitorName)));
    }

    #[test]
    fn sla_terms_accept_valid_and_return_window_count() {
        assert_eq!(validate_sla_terms(&terms()).unwrap(), 2);
        let t = SlaTerms { duration_secs: 7_201, ..terms() };
        assert_eq!(validate_sla_terms(&t).unwrap(), 3);
    }

    #[test]
    fn sla_terms_boundaries_accepted() {
        let ok = [
            SlaTerms { consensus_required: 1, ..terms() },
            SlaTerms { consensus_required: 3, ..terms() },
            SlaTerms { name: &"n".repeat(64), ..terms() },
            SlaTerms { required_uptime_bps: 1, ..terms() },
            SlaTerms { required_uptime_bps: 10_000, ..terms() },
            SlaTerms { duration_secs: 1, ..terms() },
            SlaTerms { duration_secs: MAX_SLA_DURATION_SECS, ..terms() },
            SlaTerms { check_interval_secs: 15, ..terms() }, // 3600/15 = 240 slots
            SlaTerms { timeout_ms: 100, ..terms() },
            SlaTerms { timeout_ms: 30_000, check_interval_secs: 31, ..terms() },
            SlaTerms { timeout_ms: 9_999, check_interval_secs: 10, window_secs: 2_560, ..terms() },
        ];
        for t in ok {
            assert!(validate_sla_terms(&t).is_ok());
        }
        let long_endpoint = format!("https://{}", "a".repeat(192));
        assert!(validate_sla_terms(&SlaTerms { endpoint: &long_endpoint, ..terms() }).is_ok());
    }

    #[test]
    fn sla_terms_rejections() {
        let too_long_endpoint = format!("https://{}", "a".repeat(193));
        let long_name = "n".repeat(65);
        let cases: Vec<(SlaTerms, SlaError)> = vec![
            (SlaTerms { consensus_required: 0, ..terms() }, SlaError::InvalidConsensus),
            (SlaTerms { consensus_required: 4, ..terms() }, SlaError::InvalidConsensus),
            (SlaTerms { name: "", ..terms() }, SlaError::InvalidSlaName),
            (SlaTerms { name: &long_name, ..terms() }, SlaError::InvalidSlaName),
            (SlaTerms { endpoint: "", ..terms() }, SlaError::InvalidEndpoint),
            (SlaTerms { endpoint: "http://example.com", ..terms() }, SlaError::InvalidEndpoint),
            (SlaTerms { endpoint: &too_long_endpoint, ..terms() }, SlaError::InvalidEndpoint),
            (SlaTerms { escrow_lamports: 0, ..terms() }, SlaError::ZeroEscrow),
            (SlaTerms { required_uptime_bps: 0, ..terms() }, SlaError::InvalidUptimeTarget),
            (SlaTerms { required_uptime_bps: 10_001, ..terms() }, SlaError::InvalidUptimeTarget),
            (SlaTerms { duration_secs: 0, ..terms() }, SlaError::InvalidDuration),
            (SlaTerms { duration_secs: MAX_SLA_DURATION_SECS + 1, ..terms() }, SlaError::InvalidDuration),
            // 90 days of 10-minute windows is 12_960 windows, over MAX_WINDOWS.
            (SlaTerms { window_secs: 600, duration_secs: MAX_SLA_DURATION_SECS, check_interval_secs: 10, timeout_ms: 1_000, ..terms() }, SlaError::InvalidDuration),
            (SlaTerms { check_interval_secs: 9, timeout_ms: 1_000, ..terms() }, SlaError::InvalidCheckInterval),
            (SlaTerms { check_interval_secs: 14, timeout_ms: 1_000, ..terms() }, SlaError::InvalidCheckInterval),
            (SlaTerms { timeout_ms: 99, ..terms() }, SlaError::InvalidTimeout),
            (SlaTerms { timeout_ms: 30_001, check_interval_secs: 60, ..terms() }, SlaError::InvalidTimeout),
            (SlaTerms { timeout_ms: 10_000, check_interval_secs: 10, window_secs: 2_560, ..terms() }, SlaError::InvalidTimeout),
        ];
        for (t, want) in cases {
            let got = validate_sla_terms(&t).unwrap_err();
            assert_eq!(got as u32, want as u32, "want {want:?}, got {got:?}");
        }
    }

    #[test]
    fn window_count_rounds_up() {
        assert_eq!(window_count(0, 100), 0);
        assert_eq!(window_count(1, 100), 1);
        assert_eq!(window_count(100, 100), 1);
        assert_eq!(window_count(101, 100), 2);
        assert_eq!(window_count(5, 0), 0);
    }

    #[test]
    fn window_bounds_and_short_last_window() {
        let s = schedule();
        assert_eq!(s.window_bounds(0).unwrap(), (1_000, 1_100));
        assert_eq!(s.window_bounds(1).unwrap(), (1_100, 1_200));
        assert_eq!(s.window_bounds(2).unwrap(), (1_200, 1_250));
        assert!(matches!(s.window_bounds(3), Err(SlaError::InvalidWindowIndex)));
        assert_eq!(s.slot_count(0).unwrap(), 10);
        assert_eq!(s.slot_count(2).unwrap(), 5);
        assert_eq!(s.report_deadline(2).unwrap(), 1_280);
        // 7s last window with 10s checks still has one slot.
        let s = Schedule { end_ts: 1_207, ..s };
        assert_eq!(s.slot_count(2).unwrap(), 1);
    }

    #[test]
    fn report_time_window_edges() {
        let s = schedule();
        assert!(matches!(s.check_report_time(0, 1_099), Err(SlaError::WindowNotEnded)));
        assert!(s.check_report_time(0, 1_100).is_ok());
        assert!(s.check_report_time(0, 1_129).is_ok());
        assert!(matches!(s.check_report_time(0, 1_130), Err(SlaError::ReportDeadlinePassed)));
        assert!(matches!(s.check_report_time(3, 9_999), Err(SlaError::InvalidWindowIndex)));
    }

    #[test]
    fn bitmap_validation() {
        assert!(validate_bitmaps(&[0; 32], &[0; 32], 0).is_ok());
        assert!(validate_bitmaps(&all(256), &all(256), 256).is_ok());
        assert!(validate_bitmaps(&all(10), &bits(&[0, 9]), 10).is_ok());
        // up without checked
        assert!(matches!(validate_bitmaps(&bits(&[1]), &bits(&[2]), 10), Err(SlaError::InvalidBitmap)));
        // checked past the window's slot count
        assert!(matches!(validate_bitmaps(&all(11), &[0; 32], 10), Err(SlaError::InvalidBitmap)));
        assert!(matches!(validate_bitmaps(&bits(&[255]), &[0; 32], 255), Err(SlaError::InvalidBitmap)));
    }

    #[test]
    fn bits_are_lsb_first() {
        let mut b = [0u8; 32];
        b[1] = 0b0000_0100;
        assert!(bit(&b, 10));
        assert!(!bit(&b, 9));
        assert!(!bit(&b, 2));
    }

    #[test]
    fn empty_window_counts_nothing() {
        let t = tally_window(&[None, None, None], 10, 2);
        assert_eq!((t.up, t.counted), (0, 0));
        assert_eq!(t.agreement, vec![Agreement::default(); 3]);
        let t = tally_window(&[], 10, 1);
        assert_eq!((t.up, t.counted), (0, 0));
    }

    #[test]
    fn silent_monitors_count_nothing() {
        // Every monitor reported but checked nothing.
        let t = tally_window(&[Some(([0; 32], [0; 32])); 3], 10, 1);
        assert_eq!((t.up, t.counted), (0, 0));
    }

    #[test]
    fn k_equals_one_any_vote_counts() {
        // One monitor up on 0..5, another down on 5..10.
        let a = Some((all(5), all(5)));
        let b = Some((bits(&[5, 6, 7, 8, 9]), [0; 32]));
        let t = tally_window(&[a, b], 10, 1);
        assert_eq!((t.up, t.counted), (5, 10));
        assert_eq!(t.agreement[0], Agreement { slots_voted: 5, slots_agreed: 5 });
        assert_eq!(t.agreement[1], Agreement { slots_voted: 5, slots_agreed: 5 });
    }

    #[test]
    fn k_equals_n_needs_everyone() {
        let full = Some((all(4), all(4)));
        let partial = Some((all(2), all(2)));
        let t = tally_window(&[full, partial, full], 4, 3);
        // Slots 0,1: three up votes. Slots 2,3: only two votes, not counted.
        assert_eq!((t.up, t.counted), (2, 2));
        assert_eq!(t.agreement[0], Agreement { slots_voted: 4, slots_agreed: 2 });
        assert_eq!(t.agreement[1], Agreement { slots_voted: 2, slots_agreed: 2 });
    }

    #[test]
    fn down_needs_k_votes_and_up_wins_ties() {
        // k = 2, three monitors. Slot 0: 2 up 1 down => UP. Slot 1: 1 up 2 down => DOWN.
        // Slot 2: 1 up, no other votes => not counted.
        let a = Some((bits(&[0, 1, 2]), bits(&[0, 1, 2])));
        let b = Some((bits(&[0, 1]), bits(&[0])));
        let c = Some((bits(&[0, 1]), [0; 32]));
        let t = tally_window(&[a, b, c], 3, 2);
        assert_eq!((t.up, t.counted), (1, 2));
        assert_eq!(t.agreement[0], Agreement { slots_voted: 3, slots_agreed: 1 });
        assert_eq!(t.agreement[1], Agreement { slots_voted: 2, slots_agreed: 2 });
        assert_eq!(t.agreement[2], Agreement { slots_voted: 2, slots_agreed: 1 });
    }

    #[test]
    fn tally_ignores_bits_past_slot_count() {
        let t = tally_window(&[Some((all(10), all(10)))], 4, 1);
        assert_eq!((t.up, t.counted), (4, 4));
        assert_eq!(t.agreement[0].slots_voted, 4);
    }

    #[test]
    fn full_window_of_256_slots() {
        let t = tally_window(&[Some((all(256), all(256)))], 256, 1);
        assert_eq!((t.up, t.counted), (256, 256));
    }

    #[test]
    fn payout_threshold() {
        // No counted checks: always the customer.
        assert!(!provider_is_paid(0, 0, 1));
        assert!(!provider_is_paid(0, 0, 10_000));
        // Exactly at the threshold pays the provider.
        assert!(provider_is_paid(999, 1_000, 9_990));
        assert!(!provider_is_paid(998, 1_000, 9_990));
        assert!(provider_is_paid(1, 1, 10_000));
        assert!(!provider_is_paid(9_999, 10_000, 10_000));
        assert!(provider_is_paid(0, 10_000, 0));
        // No overflow at the extremes.
        assert!(provider_is_paid(u64::MAX, u64::MAX, 10_000));
        assert!(!provider_is_paid(u64::MAX - 1, u64::MAX, 10_000));
    }
}
