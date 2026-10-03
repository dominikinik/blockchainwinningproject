//! `submit_report` and `finalize_window`.
mod common;

use anchor_lang::prelude::{AccountMeta, Pubkey};
use common::*;
use sla::{
    error::SlaError,
    state::{WindowReport, WindowResult},
};
use solana_signer::Signer;

const FULL: u32 = 10; // slots in windows 0 and 1

// --- submit_report ---

#[test]
fn first_report_creates_window_report() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    env.set_time(env.window_end(&s.key, 0));
    let m = &s.monitors[1];
    let before = env.balance(&m.pubkey());
    let meta = assert_ok(env.submit_report(m, &s.key, 0, bits(0..FULL), bits(0..5)));

    let report: WindowReport = env.read(&window_pda(&s.key, 0));
    assert_eq!(report.sla, s.key);
    assert_eq!(report.window_index, 0);
    assert_eq!(report.payer, m.pubkey());
    assert!(!report.per_monitor[0].submitted);
    assert!(report.per_monitor[1].submitted);
    assert_eq!(report.per_monitor[1].checked, bits(0..FULL));
    assert_eq!(report.per_monitor[1].up, bits(0..5));
    assert!(report.per_monitor[3..].iter().all(|r| *r == Default::default()));
    assert_eq!(env.monitor(&m.pubkey()).reports_submitted, 1);

    let rent = env.balance(&window_pda(&s.key, 0));
    assert_eq!(before - env.balance(&m.pubkey()), rent + meta.fee);
    assert!(meta.logs.iter().any(|l| l.starts_with("Program data:")), "ReportSubmitted event");
}

#[test]
fn second_report_keeps_payer_and_fills_its_slot() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    env.set_time(env.window_end(&s.key, 0) + 5);
    assert_ok(env.submit_report(&s.monitors[2], &s.key, 0, bits(0..FULL), bits(0..FULL)));
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, bits(0..3), [0; 32]));
    let report: WindowReport = env.read(&window_pda(&s.key, 0));
    assert_eq!(report.payer, s.monitors[2].pubkey());
    assert!(report.per_monitor[0].submitted && report.per_monitor[2].submitted);
    assert!(!report.per_monitor[1].submitted);
    assert_eq!(report.per_monitor[0].checked, bits(0..3));
}

#[test]
fn report_accepted_until_just_before_deadline() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64 - 1);
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, [0; 32], [0; 32]));
}

#[test]
fn report_for_short_last_window_is_limited_to_its_slots() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2));
    assert_err(env.submit_report(&s.monitors[0], &s.key, 2, bits(0..6), [0; 32]), SlaError::InvalidBitmap);
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 2, bits(0..5), bits(0..5)));
}

#[test]
fn report_rejects_unassigned_signer() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let outsider = env.new_monitor(); // registered, but not on this SLA
    env.set_time(env.window_end(&s.key, 0));
    assert_err(env.submit_report(&outsider, &s.key, 0, [0; 32], [0; 32]), SlaError::NotAssignedMonitor);
}

#[test]
fn report_rejects_unregistered_signer() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let stranger = env.funded();
    env.set_time(env.window_end(&s.key, 0));
    // No Monitor PDA exists for the stranger, so account validation fails first.
    assert!(env.submit_report(&stranger, &s.key, 0, [0; 32], [0; 32]).is_err());
}

#[test]
fn report_rejects_signing_for_another_monitor() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0));
    // monitor 1 signs, but passes monitor 0's PDA.
    let mut ix = env.submit_report_ix(&s.monitors[1].pubkey(), &s.key, 0, [0; 32], [0; 32]);
    ix.accounts[1] = AccountMeta::new(monitor_pda(&s.monitors[0].pubkey()), false);
    let m1 = s.monitors[1].insecure_clone();
    assert_anchor_err(env.send(&[ix], &m1, &[]), anchor_lang::error::ErrorCode::ConstraintSeeds);
}

#[test]
fn report_rejects_bad_window_index() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2));
    assert_err(env.submit_report(&s.monitors[0], &s.key, 3, [0; 32], [0; 32]), SlaError::InvalidWindowIndex);
    assert_err(env.submit_report(&s.monitors[0], &s.key, u32::MAX, [0; 32], [0; 32]), SlaError::InvalidWindowIndex);
}

#[test]
fn report_rejects_early_report() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) - 1);
    assert_err(env.submit_report(&s.monitors[0], &s.key, 0, [0; 32], [0; 32]), SlaError::WindowNotEnded);
    assert!(!env.exists(&window_pda(&s.key, 0)));
}

#[test]
fn report_rejects_late_report() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    assert_err(env.submit_report(&s.monitors[0], &s.key, 0, [0; 32], [0; 32]), SlaError::ReportDeadlinePassed);
}

#[test]
fn report_rejects_duplicate() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0));
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, bits(0..FULL), bits(0..FULL)));
    assert_err(env.submit_report(&s.monitors[0], &s.key, 0, bits(0..FULL), [0; 32]), SlaError::DuplicateReport);
    let report: WindowReport = env.read(&window_pda(&s.key, 0));
    assert_eq!(report.per_monitor[0].up, bits(0..FULL), "first report is kept");
    assert_eq!(env.monitor(&s.monitors[0].pubkey()).reports_submitted, 1);
}

#[test]
fn report_rejects_invalid_bitmaps() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0));
    let m = &s.monitors[0];
    // up without checked
    assert_err(env.submit_report(m, &s.key, 0, bits([1]), bits([2])), SlaError::InvalidBitmap);
    // bit past the window's 10 slots
    assert_err(env.submit_report(m, &s.key, 0, bits([10]), [0; 32]), SlaError::InvalidBitmap);
    assert_err(env.submit_report(m, &s.key, 0, bits([255]), bits([255])), SlaError::InvalidBitmap);
}

#[test]
fn deactivated_monitor_still_reports_on_existing_sla() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let admin = env.admin.insecure_clone();
    assert_ok(env.set_monitor_active_as(&admin, &s.monitors[0].pubkey(), false));
    env.set_time(env.window_end(&s.key, 0));
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, [0; 32], [0; 32]));
}

// --- finalize_window ---

#[test]
fn finalize_applies_consensus_and_closes_report() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    env.set_time(env.window_end(&s.key, 0));
    // Slots 0..6: all three up. Slots 6..8: m0 up, m1+m2 down => DOWN.
    // Slot 8: only m0 checked => not counted. Slot 9: nobody checked.
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, bits(0..9), bits(0..9)));
    assert_ok(env.submit_report(&s.monitors[1], &s.key, 0, bits(0..8), bits(0..6)));
    assert_ok(env.submit_report(&s.monitors[2], &s.key, 0, bits(0..8), bits(0..6)));

    let report_key = window_pda(&s.key, 0);
    let rent = env.balance(&report_key);
    let payer_before = env.balance(&s.monitors[0].pubkey());
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    let meta = assert_ok(env.finalize(&cranker, &s.key, 0));

    let sla = env.sla(&s.key);
    assert_eq!(sla.window_results, vec![WindowResult { up: 6, counted: 8 }]);
    assert_eq!((sla.up_checks, sla.counted_checks), (6, 8));
    assert_eq!(sla.next_window_to_finalize, 1);

    // m0 voted 9, agreed on 6 UP slots. m1/m2 voted 8, agreed on all 8.
    let m0 = env.monitor(&s.monitors[0].pubkey());
    let m1 = env.monitor(&s.monitors[1].pubkey());
    assert_eq!((m0.slots_voted, m0.slots_agreed), (9, 6));
    assert_eq!((m1.slots_voted, m1.slots_agreed), (8, 8));

    assert!(!env.exists(&report_key), "report closed");
    assert_eq!(env.balance(&s.monitors[0].pubkey()) - payer_before, rent, "rent refunded to payer");
    assert!(meta.logs.iter().any(|l| l.starts_with("Program data:")), "WindowFinalized event");
}

#[test]
fn finalize_without_reports_counts_nothing() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    assert_ok(env.finalize(&cranker, &s.key, 0));
    let sla = env.sla(&s.key);
    assert_eq!(sla.window_results, vec![WindowResult { up: 0, counted: 0 }]);
    assert_eq!(sla.next_window_to_finalize, 1);
}

#[test]
fn finalize_ignores_lamports_sent_to_an_empty_report_address() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.svm.airdrop(&window_pda(&s.key, 0), SOL).unwrap();
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    assert_ok(env.finalize(&cranker, &s.key, 0));
    assert_eq!(env.sla(&s.key).window_results[0], WindowResult { up: 0, counted: 0 });
}

#[test]
fn finalize_rejects_before_deadline() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64 - 1);
    let cranker = env.funded();
    assert_err(env.finalize(&cranker, &s.key, 0), SlaError::ReportDeadlineNotPassed);
}

#[test]
fn finalize_rejects_out_of_order_and_repeat() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2) + GRACE as i64);
    let cranker = env.funded();
    assert_err(env.finalize(&cranker, &s.key, 1), SlaError::WindowOutOfOrder);
    assert_ok(env.finalize(&cranker, &s.key, 0));
    assert_err(env.finalize(&cranker, &s.key, 0), SlaError::WindowOutOfOrder);
    assert_ok(env.finalize(&cranker, &s.key, 1));
}

#[test]
fn finalize_rejects_bad_window_index() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2) + GRACE as i64);
    let cranker = env.funded();
    for i in 0..3 {
        assert_ok(env.finalize(&cranker, &s.key, i));
    }
    assert_err(env.finalize(&cranker, &s.key, 3), SlaError::InvalidWindowIndex);
}

#[test]
fn finalize_rejects_wrong_payer() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0));
    assert_ok(env.submit_report(&s.monitors[1], &s.key, 0, [0; 32], [0; 32]));
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    let remaining = env.monitor_metas(&s.key);
    let res = env.finalize_raw(&cranker, &s.key, 0, window_pda(&s.key, 0), cranker.pubkey(), remaining);
    assert_err(res, SlaError::PayerMismatch);
}

#[test]
fn finalize_cannot_hide_the_window_report() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0));
    assert_ok(env.submit_report(&s.monitors[0], &s.key, 0, bits(0..FULL), [0; 32]));
    env.set_time(env.window_end(&s.key, 1) + GRACE as i64);
    let cranker = env.funded();
    let remaining = env.monitor_metas(&s.key);
    // Pass window 1's (empty) PDA, or any other account, in place of window 0's report.
    for fake in [window_pda(&s.key, 1), Pubkey::new_unique()] {
        let res = env.finalize_raw(&cranker, &s.key, 0, fake, cranker.pubkey(), remaining.clone());
        assert_anchor_err(res, anchor_lang::error::ErrorCode::ConstraintSeeds);
    }
    assert_eq!(env.sla(&s.key).next_window_to_finalize, 0);
}

#[test]
fn finalize_rejects_mismatched_monitor_accounts() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let outsider = env.new_monitor();
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    let pdas: Vec<Pubkey> = s.monitors.iter().map(|m| monitor_pda(&m.pubkey())).collect();
    let w = |k: &Pubkey| AccountMeta::new(*k, false);
    let cases = vec![
        vec![],
        vec![w(&pdas[0])],
        vec![w(&pdas[1]), w(&pdas[0])],
        vec![w(&pdas[0]), w(&monitor_pda(&outsider.pubkey()))],
        vec![w(&pdas[0]), AccountMeta::new_readonly(pdas[1], false)], // not writable
        vec![w(&pdas[0]), w(&pdas[1]), w(&pdas[1])],
    ];
    for remaining in cases {
        let res = env.finalize_raw(&cranker, &s.key, 0, window_pda(&s.key, 0), cranker.pubkey(), remaining);
        assert_err(res, SlaError::MonitorAccountMismatch);
    }
}

#[test]
fn report_after_finalize_is_rejected() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 0) + GRACE as i64);
    let cranker = env.funded();
    assert_ok(env.finalize(&cranker, &s.key, 0));
    assert_err(env.submit_report(&s.monitors[0], &s.key, 0, [0; 32], [0; 32]), SlaError::ReportDeadlinePassed);
    assert!(!env.exists(&window_pda(&s.key, 0)));
}

#[test]
fn worst_case_finalize_fits_the_default_compute_budget() {
    // 2_560s windows with 10s checks: 256 slots, 5 monitors, all reporting every slot.
    let mut env = Env::bare();
    let admin = env.admin.insecure_clone();
    assert_ok(env.initialize_config(&admin, 2_560, GRACE, 5));
    let customer = env.funded();
    let monitors: Vec<_> = (0..5).map(|_| env.new_monitor()).collect();
    let keys: Vec<Pubkey> = monitors.iter().map(|m| m.pubkey()).collect();
    let params = sla::instructions::CreateSlaParams {
        consensus_required: 3,
        duration_secs: 2_560,
        ..default_params(Pubkey::new_unique())
    };
    assert_ok(env.create_sla_with(&customer, [1; 16], params, &keys));
    let key = sla_pda(&customer.pubkey(), &[1; 16]);

    env.set_time(env.window_end(&key, 0));
    for (i, m) in monitors.iter().enumerate() {
        let up = if i % 2 == 0 { bits(0..256) } else { bits((0..256).step_by(2)) };
        assert_ok(env.submit_report(m, &key, 0, bits(0..256), up));
    }
    env.set_time(env.window_end(&key, 0) + GRACE as i64);
    let cranker = env.funded();
    let meta = assert_ok(env.finalize(&cranker, &key, 0));
    assert!(meta.compute_units_consumed < 200_000, "{} CU", meta.compute_units_consumed);
    assert_eq!(env.sla(&key).window_results, vec![WindowResult { up: 256, counted: 256 }]);
    println!("finalize_window worst case: {} CU", meta.compute_units_consumed);
}
