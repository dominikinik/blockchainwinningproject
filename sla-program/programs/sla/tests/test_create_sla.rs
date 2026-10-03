//! `create_sla`: happy path, escrow transfer, and every validation error.
mod common;

use anchor_lang::prelude::{AccountMeta, Pubkey};
use common::*;
use sla::{error::SlaError, instructions::CreateSlaParams, state::Sla, MAX_SLA_DURATION_SECS};
use solana_keypair::Keypair;
use solana_signer::Signer;

struct Setup {
    env: Env,
    customer: Keypair,
    provider: Pubkey,
    monitors: Vec<Pubkey>,
}

fn setup(n: usize) -> Setup {
    let mut env = Env::new();
    let customer = env.funded();
    let monitors = (0..n).map(|_| env.new_monitor().pubkey()).collect();
    Setup { env, customer, provider: Pubkey::new_unique(), monitors }
}

impl Setup {
    fn create(&mut self, params: CreateSlaParams) -> TxResult {
        let monitors = self.monitors.clone();
        self.env.create_sla_with(&self.customer, [9; 16], params, &monitors)
    }

    fn params(&self) -> CreateSlaParams {
        default_params(self.provider)
    }
}

#[test]
fn create_sla_stores_terms_and_moves_escrow() {
    let mut s = setup(3);
    let before = s.env.balance(&s.customer.pubkey());
    let meta = assert_ok(s.create(s.params()));
    let key = sla_pda(&s.customer.pubkey(), &[9; 16]);
    let sla: Sla = s.env.sla(&key);

    assert_eq!(sla.customer, s.customer.pubkey());
    assert_eq!(sla.provider, s.provider);
    assert_eq!(sla.sla_id, [9; 16]);
    assert_eq!(sla.monitors, s.monitors);
    assert_eq!(sla.name, "api");
    assert_eq!(sla.endpoint, "https://example.com/health");
    assert_eq!(sla.escrow_lamports, ESCROW);
    assert_eq!(sla.required_uptime_bps, 9_000);
    assert_eq!((sla.start_ts, sla.end_ts), (T0, T0 + DURATION as i64));
    assert_eq!((sla.check_interval_secs, sla.timeout_ms, sla.consensus_required), (INTERVAL, 2_000, 2));
    assert_eq!((sla.window_secs, sla.report_grace_secs), (WINDOW, GRACE));
    assert_eq!(sla.total_windows, 3);
    assert_eq!(sla.next_window_to_finalize, 0);
    assert_eq!((sla.up_checks, sla.counted_checks), (0, 0));
    assert!(sla.window_results.is_empty());
    assert!(!sla.settled);
    assert_eq!(sla.recipient, None);

    let size = Sla::space(3);
    let rent = s.env.svm.minimum_balance_for_rent_exemption(size);
    let acc = s.env.svm.get_account(&key).unwrap();
    assert_eq!(acc.data.len(), size);
    assert_eq!(acc.lamports, rent + ESCROW);
    assert_eq!(before - s.env.balance(&s.customer.pubkey()), rent + ESCROW + meta.fee);
    assert!(meta.logs.iter().any(|l| l.starts_with("Program data:")), "SlaCreated event");
}

#[test]
fn create_sla_with_max_monitors_and_k_equals_n() {
    let mut s = setup(5);
    let params = CreateSlaParams { consensus_required: 5, ..s.params() };
    assert_ok(s.create(params));
}

#[test]
fn create_sla_with_same_id_twice_fails() {
    let mut s = setup(1);
    let params = CreateSlaParams { consensus_required: 1, ..s.params() };
    assert_ok(s.create(params.clone()));
    assert!(s.create(params).is_err());
}

#[test]
fn create_sla_monitor_count_limits() {
    let mut s = setup(3);
    s.monitors.clear();
    assert_err(s.create(s.params()), SlaError::InvalidMonitorCount);

    // Config allows at most 2.
    let mut env = Env::bare();
    let admin = env.admin.insecure_clone();
    assert_ok(env.initialize_config(&admin, WINDOW, GRACE, 2));
    let customer = env.funded();
    let monitors: Vec<Pubkey> = (0..3).map(|_| env.new_monitor().pubkey()).collect();
    let res = env.create_sla_with(&customer, [1; 16], default_params(Pubkey::new_unique()), &monitors);
    assert_err(res, SlaError::InvalidMonitorCount);
}

#[test]
fn create_sla_rejects_duplicate_monitor() {
    let mut s = setup(2);
    s.monitors.push(s.monitors[0]);
    assert_err(s.create(s.params()), SlaError::DuplicateMonitor);
}

#[test]
fn create_sla_remaining_accounts_must_match_monitors() {
    let mut s = setup(2);
    let pdas: Vec<Pubkey> = s.monitors.iter().map(monitor_pda).collect();
    let customer = s.customer.insecure_clone();
    let ro = |k: &Pubkey| AccountMeta::new_readonly(*k, false);
    let cases: Vec<Vec<AccountMeta>> = vec![
        vec![],                                    // missing
        vec![ro(&pdas[0])],                        // too few
        vec![ro(&pdas[1]), ro(&pdas[0])],          // wrong order
        vec![ro(&pdas[0]), ro(&pdas[1]), ro(&pdas[1])], // too many
        vec![ro(&pdas[0]), ro(&config_pda())],     // a program account of another type
        vec![ro(&pdas[0]), ro(&customer.pubkey())], // not a program account
    ];
    for remaining in cases {
        let res = s.env.create_sla_raw(&customer, [9; 16], s.params(), s.monitors.clone(), remaining);
        assert_err(res, SlaError::MonitorAccountMismatch);
    }
}

#[test]
fn create_sla_rejects_unregistered_monitor() {
    let mut s = setup(1);
    s.monitors.push(Pubkey::new_unique());
    assert_err(s.create(s.params()), SlaError::MonitorAccountMismatch);
}

#[test]
fn create_sla_rejects_inactive_monitor() {
    let mut s = setup(2);
    let admin = s.env.admin.insecure_clone();
    assert_ok(s.env.set_monitor_active_as(&admin, &s.monitors[1], false));
    assert_err(s.create(s.params()), SlaError::MonitorInactive);
}

#[test]
fn create_sla_rejects_provider_is_customer() {
    let mut s = setup(2);
    s.provider = s.customer.pubkey();
    assert_err(s.create(s.params()), SlaError::ProviderIsCustomer);
}

#[test]
fn create_sla_rejects_default_provider() {
    let mut s = setup(2);
    s.provider = Pubkey::default();
    assert_err(s.create(s.params()), SlaError::InvalidParams);
}

#[test]
fn create_sla_rejects_invalid_terms() {
    let base = default_params(Pubkey::new_unique());
    let cases: Vec<(CreateSlaParams, SlaError)> = vec![
        (CreateSlaParams { consensus_required: 0, ..base.clone() }, SlaError::InvalidConsensus),
        (CreateSlaParams { consensus_required: 4, ..base.clone() }, SlaError::InvalidConsensus),
        (CreateSlaParams { name: String::new(), ..base.clone() }, SlaError::InvalidSlaName),
        (CreateSlaParams { name: "n".repeat(65), ..base.clone() }, SlaError::InvalidSlaName),
        (CreateSlaParams { endpoint: String::new(), ..base.clone() }, SlaError::InvalidEndpoint),
        (CreateSlaParams { endpoint: "http://example.com".into(), ..base.clone() }, SlaError::InvalidEndpoint),
        (CreateSlaParams { endpoint: format!("https://{}", "a".repeat(193)), ..base.clone() }, SlaError::InvalidEndpoint),
        (CreateSlaParams { escrow_lamports: 0, ..base.clone() }, SlaError::ZeroEscrow),
        (CreateSlaParams { required_uptime_bps: 0, ..base.clone() }, SlaError::InvalidUptimeTarget),
        (CreateSlaParams { required_uptime_bps: 10_001, ..base.clone() }, SlaError::InvalidUptimeTarget),
        (CreateSlaParams { duration_secs: 0, ..base.clone() }, SlaError::InvalidDuration),
        (CreateSlaParams { duration_secs: MAX_SLA_DURATION_SECS + 1, ..base.clone() }, SlaError::InvalidDuration),
        // 90 days of 100s windows is far more than 2_160 windows.
        (CreateSlaParams { duration_secs: MAX_SLA_DURATION_SECS, ..base.clone() }, SlaError::InvalidDuration),
        (CreateSlaParams { check_interval_secs: 9, timeout_ms: 1_000, ..base.clone() }, SlaError::InvalidCheckInterval),
        (CreateSlaParams { timeout_ms: 99, ..base.clone() }, SlaError::InvalidTimeout),
        (CreateSlaParams { timeout_ms: 30_001, check_interval_secs: 60, ..base.clone() }, SlaError::InvalidTimeout),
        (CreateSlaParams { timeout_ms: 10_000, ..base.clone() }, SlaError::InvalidTimeout),
    ];
    let mut s = setup(3);
    for (mut params, want) in cases {
        params.provider = s.provider;
        assert_err(s.create(params), want);
    }
    // Nothing was created by the failures.
    assert!(!s.env.exists(&sla_pda(&s.customer.pubkey(), &[9; 16])));
}

#[test]
fn create_sla_check_interval_must_fit_256_checks() {
    // 3_600s windows: 14s checks would need 258 slots.
    let mut env = Env::bare();
    let admin = env.admin.insecure_clone();
    assert_ok(env.initialize_config(&admin, 3_600, 600, 5));
    let customer = env.funded();
    let m = env.new_monitor().pubkey();
    let base = CreateSlaParams { consensus_required: 1, duration_secs: 7_200, ..default_params(Pubkey::new_unique()) };
    let res = env.create_sla_with(&customer, [1; 16], CreateSlaParams { check_interval_secs: 14, ..base.clone() }, &[m]);
    assert_err(res, SlaError::InvalidCheckInterval);
    assert_ok(env.create_sla_with(&customer, [1; 16], CreateSlaParams { check_interval_secs: 15, ..base }, &[m]));
}

#[test]
fn create_sla_needs_funds_for_escrow() {
    let mut s = setup(2);
    let params = CreateSlaParams { escrow_lamports: 1_000 * SOL, ..s.params() };
    assert!(s.create(params).is_err());
}
