use anchor_lang::{prelude::Pubkey, AccountSerialize};
use sla::{
    state::{Sla, WindowResult},
    DEFAULT_WINDOW_SECS, MAX_SLA_DURATION_SECS, MAX_WINDOWS,
};

/// An account created by CPI can be at most 10 KiB.
const MAX_CPI_ACCOUNT_SIZE: usize = 10_240;

#[test]
fn max_duration_fits_max_windows() {
    assert_eq!(Sla::window_count(MAX_SLA_DURATION_SECS, DEFAULT_WINDOW_SECS), MAX_WINDOWS);
}

#[test]
fn largest_sla_account_fits_a_cpi_allocation() {
    assert!(Sla::space(MAX_WINDOWS) < MAX_CPI_ACCOUNT_SIZE);
}

#[test]
fn window_count_rounds_up_partial_windows() {
    assert_eq!(Sla::window_count(1, 3_600), 1);
    assert_eq!(Sla::window_count(3_600, 3_600), 1);
    assert_eq!(Sla::window_count(3_601, 3_600), 2);
    assert_eq!(Sla::window_count(0, 3_600), 0);
    assert_eq!(Sla::window_count(100, 0), 0);
}

/// Monitor nodes and the frontend filter `getProgramAccounts` on these offsets (SPEC.md).
#[test]
fn sla_memcmp_offsets_are_fixed() {
    let customer = Pubkey::new_unique();
    let provider = Pubkey::new_unique();
    let monitors = vec![Pubkey::new_unique(), Pubkey::new_unique()];
    let sla = Sla {
        customer,
        provider,
        sla_id: [7; 16],
        monitors: monitors.clone(),
        name: "a name of any length".into(),
        endpoint: "https://example.com".into(),
        escrow_lamports: 1,
        required_uptime_bps: 9_990,
        start_ts: 0,
        end_ts: 1,
        check_interval_secs: 60,
        timeout_ms: 2_000,
        consensus_required: 1,
        window_secs: 3_600,
        report_grace_secs: 600,
        total_windows: 1,
        next_window_to_finalize: 0,
        up_checks: 0,
        counted_checks: 0,
        window_results: vec![WindowResult::default()],
        settled: false,
        recipient: None,
        bump: 255,
    };
    let mut data = Vec::new();
    sla.try_serialize(&mut data).unwrap();

    assert_eq!(&data[8..40], customer.as_ref());
    assert_eq!(&data[40..72], provider.as_ref());
    assert_eq!(&data[72..88], &[7; 16]);
    assert_eq!(&data[88..92], &2u32.to_le_bytes());
    assert_eq!(&data[92..124], monitors[0].as_ref());
    assert_eq!(&data[124..156], monitors[1].as_ref());
}
