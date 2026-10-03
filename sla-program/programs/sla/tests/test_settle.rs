//! `settle`, full SLA lifecycles, and lamport conservation.
mod common;

use anchor_lang::prelude::Pubkey;
use common::*;
use sla::{
    error::SlaError,
    state::{Recipient, Sla},
};
use solana_keypair::Keypair;
use solana_signer::Signer;

/// Runs every window of `s` with each monitor reporting `up_slots` of each window up (all slots
/// checked), then finalizes them all. Returns the cranker.
fn run_all_windows(env: &mut Env, s: &TestSla, up_slots: impl Fn(u32) -> u32) -> Keypair {
    let cranker = env.funded();
    for i in 0..3 {
        let slots = env.sla(&s.key).schedule().slot_count(i).unwrap();
        env.set_time(env.window_end(&s.key, i));
        for m in &s.monitors {
            assert_ok(env.submit_report(m, &s.key, i, bits(0..slots), bits(0..up_slots(slots))));
        }
    }
    env.set_time(env.window_end(&s.key, 2) + GRACE as i64);
    for i in 0..3 {
        assert_ok(env.finalize(&cranker, &s.key, i));
    }
    cranker
}

fn rent(env: &Env) -> u64 {
    env.svm.minimum_balance_for_rent_exemption(Sla::space(3))
}

#[test]
fn provider_paid_when_target_met() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    let cranker = run_all_windows(&mut env, &s, |n| n); // 100% up
    let before = env.balance(&s.provider.pubkey());
    let customer_before = env.balance(&s.customer.pubkey());
    let meta = assert_ok(env.settle(&cranker, &s.key));

    assert_eq!(env.balance(&s.provider.pubkey()) - before, ESCROW);
    assert_eq!(env.balance(&s.customer.pubkey()), customer_before);
    assert_eq!(env.balance(&s.key), rent(&env), "only the rent reserve stays");
    let sla = env.sla(&s.key);
    assert!(sla.settled);
    assert_eq!(sla.recipient, Some(Recipient::Provider));
    assert_eq!((sla.up_checks, sla.counted_checks), (25, 25));
    assert!(meta.logs.iter().any(|l| l.starts_with("Program data:")), "SlaSettled event");
}

#[test]
fn customer_refunded_when_target_missed() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    // 9 of 10 and 4 of 5 slots up: 22/25 = 88% < 90%.
    let cranker = run_all_windows(&mut env, &s, |n| n - 1);
    let before = env.balance(&s.customer.pubkey());
    assert_ok(env.settle(&cranker, &s.key));
    assert_eq!(env.balance(&s.customer.pubkey()) - before, ESCROW);
    assert_eq!(env.balance(&s.provider.pubkey()), 0);
    assert_eq!(env.sla(&s.key).recipient, Some(Recipient::Customer));
}

#[test]
fn exactly_at_threshold_pays_provider() {
    let mut env = Env::new();
    // 9 of 10 up in every window of a 300s SLA: 27/30 = 90% exactly.
    let customer = env.funded();
    let provider = Keypair::new();
    let m = env.new_monitor();
    let params = sla::instructions::CreateSlaParams {
        consensus_required: 1,
        duration_secs: 300,
        ..default_params(provider.pubkey())
    };
    assert_ok(env.create_sla_with(&customer, [1; 16], params, &[m.pubkey()]));
    let s = TestSla { key: sla_pda(&customer.pubkey(), &[1; 16]), customer, provider, monitors: vec![m] };
    let cranker = run_all_windows(&mut env, &s, |n| n - 1);
    assert_ok(env.settle(&cranker, &s.key));
    let sla = env.sla(&s.key);
    assert_eq!((sla.up_checks, sla.counted_checks), (27, 30));
    assert_eq!(sla.recipient, Some(Recipient::Provider));
    assert_eq!(env.balance(&s.provider.pubkey()), ESCROW);
}

#[test]
fn silent_monitors_refund_customer() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2) + GRACE as i64);
    let cranker = env.funded();
    for i in 0..3 {
        assert_ok(env.finalize(&cranker, &s.key, i));
    }
    let before = env.balance(&s.customer.pubkey());
    assert_ok(env.settle(&cranker, &s.key));
    let sla = env.sla(&s.key);
    assert_eq!(sla.counted_checks, 0);
    assert_eq!(sla.recipient, Some(Recipient::Customer));
    assert_eq!(env.balance(&s.customer.pubkey()) - before, ESCROW);
}

#[test]
fn settle_rejects_early() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let cranker = env.funded();
    env.set_time(T0 + DURATION as i64 + GRACE as i64 - 1);
    assert_err(env.settle(&cranker, &s.key), SlaError::NotYetSettleable);
}

#[test]
fn settle_rejects_unfinalized_windows() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    env.set_time(env.window_end(&s.key, 2) + GRACE as i64);
    let cranker = env.funded();
    assert_err(env.settle(&cranker, &s.key), SlaError::WindowsNotFinalized);
    assert_ok(env.finalize(&cranker, &s.key, 0));
    assert_ok(env.finalize(&cranker, &s.key, 1));
    assert_err(env.settle(&cranker, &s.key), SlaError::WindowsNotFinalized);
}

#[test]
fn settle_twice_fails() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let cranker = run_all_windows(&mut env, &s, |n| n);
    assert_ok(env.settle(&cranker, &s.key));
    let provider = env.balance(&s.provider.pubkey());
    assert_err(env.settle(&cranker, &s.key), SlaError::AlreadySettled);
    assert_eq!(env.balance(&s.provider.pubkey()), provider);
    assert_eq!(env.balance(&s.key), rent(&env));
}

#[test]
fn settle_rejects_substituted_recipients() {
    let mut env = Env::new();
    let s = env.create_default_sla(2, 1);
    let cranker = run_all_windows(&mut env, &s, |n| n);
    let thief = Pubkey::new_unique();
    let res = env.settle_raw(&cranker, &s.key, s.customer.pubkey(), thief);
    assert_anchor_err(res, anchor_lang::error::ErrorCode::ConstraintAddress);
    let res = env.settle_raw(&cranker, &s.key, thief, s.provider.pubkey());
    assert_anchor_err(res, anchor_lang::error::ErrorCode::ConstraintAddress);
    assert!(!env.sla(&s.key).settled);
}

#[test]
fn lamports_are_conserved_across_the_lifecycle() {
    let mut env = Env::new();
    let s = env.create_default_sla(3, 2);
    let rent = rent(&env);
    assert_eq!(env.balance(&s.key), rent + ESCROW, "escrow in");

    // Every report account's rent goes back to its payer; the SLA balance never moves.
    let cranker = env.funded();
    for i in 0..3 {
        env.set_time(env.window_end(&s.key, i));
        let payer = &s.monitors[i as usize];
        let before = env.balance(&payer.pubkey());
        let meta = assert_ok(env.submit_report(payer, &s.key, i, bits(0..5), bits(0..5)));
        let report_rent = env.balance(&window_pda(&s.key, i));
        assert_eq!(before - env.balance(&payer.pubkey()), report_rent + meta.fee);
        env.set_time(env.window_end(&s.key, i) + GRACE as i64);
        let before = env.balance(&payer.pubkey());
        assert_ok(env.finalize(&cranker, &s.key, i));
        assert_eq!(env.balance(&payer.pubkey()) - before, report_rent);
        assert_eq!(env.balance(&s.key), rent + ESCROW);
    }

    // k = 2 but each window had one report: nothing counted, so the customer gets the escrow.
    let customer_before = env.balance(&s.customer.pubkey());
    let provider_before = env.balance(&s.provider.pubkey());
    assert_ok(env.settle(&cranker, &s.key));
    let paid_out = (env.balance(&s.customer.pubkey()) - customer_before)
        + (env.balance(&s.provider.pubkey()) - provider_before);
    assert_eq!(paid_out, ESCROW, "escrow out");
    assert_eq!(env.balance(&s.key), rent);
}
