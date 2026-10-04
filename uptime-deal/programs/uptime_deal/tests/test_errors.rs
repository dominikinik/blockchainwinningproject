//! Every rejected instruction, and that a rejection moves no lamports and changes no counter.
mod common;

use common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use uptime_deal::{error::DealError, MAX_DEAL_DURATION_SECONDS, MAX_ROUNDS, MIN_DEAL_LAMPORTS, OBSERVATION_GRACE_SECONDS};

// Anchor error codes.
const ACCOUNT_ALREADY_IN_USE: u32 = 0; // system program: account already exists
const CONSTRAINT_HAS_ONE: u32 = 2001;
const ACCOUNT_NOT_INITIALIZED: u32 = 3012;

/// A funded key that is none of the deal's parties.
fn stranger(env: &mut Env) -> Keypair {
    let key = Keypair::new();
    env.svm.airdrop(&key.pubkey(), SOL).unwrap();
    key
}

#[test]
fn create_rejects_amount_below_minimum() {
    let mut env = Env::new();
    for amount in [0, MIN_DEAL_LAMPORTS - 1] {
        assert_err(env.create_with(1, Terms { amount, ..Terms::default() }), DealError::AmountTooSmall);
    }
    assert!(!env.exists(&env.pda(1)));

    // The minimum itself is accepted, and paying it to a brand-new wallet works.
    assert_ok(env.create_with(1, Terms { amount: MIN_DEAL_LAMPORTS, ..Terms::default() }));
    env.observe_all(1, &pattern(10, 0, 0));
    env.to_settlement(1);
    assert_ok(env.settle(1));
    assert_eq!(env.balance(&env.recipient.pubkey()), MIN_DEAL_LAMPORTS);
}

#[test]
fn create_rejects_payer_as_recipient() {
    let mut env = Env::new();
    let payer = env.payer.insecure_clone();
    let ix = Env::create_ix(&payer.pubkey(), &payer.pubkey(), &env.oracle.pubkey(), 1, Terms::default());
    assert_err(env.send(&[ix], &payer, &[]), DealError::RecipientIsPayer);
}

#[test]
fn create_rejects_duplicate_deal_id() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    assert_anchor_code(env.create(1), ACCOUNT_ALREADY_IN_USE);
    assert_eq!(env.deal(&env.pda(1)).amount_lamports, AMOUNT);
}

#[test]
fn create_fails_when_payer_cannot_fund() {
    let mut env = Env::new();
    let poor = stranger(&mut env);
    let terms = Terms { amount: 5 * SOL, ..Terms::default() };
    let ix = Env::create_ix(&poor.pubkey(), &env.recipient.pubkey(), &env.oracle.pubkey(), 1, terms);
    assert!(env.send(&[ix], &poor, &[]).is_err());
    assert!(!env.exists(&deal_pda(&poor.pubkey(), 1)));
}

#[test]
fn create_requires_payer_signature() {
    let mut env = Env::new();
    let oracle = env.oracle.insecure_clone();
    let mut ix = Env::create_ix(&env.payer.pubkey(), &env.recipient.pubkey(), &oracle.pubkey(), 1, Terms::default());
    ix.accounts[0].is_signer = false; // try to spend the payer's lamports without its signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
}

#[test]
fn create_rejects_invalid_duration() {
    let mut env = Env::new();
    for duration in [0, MAX_DEAL_DURATION_SECONDS + 1] {
        assert_err(env.create_with(1, Terms { duration, interval: 1, ..Terms::default() }), DealError::InvalidDuration);
    }
    assert!(!env.exists(&env.pda(1)));

    assert_ok(env.create_with(1, Terms { duration: 1, interval: 1, ..Terms::default() }));
    let longest = Terms { duration: MAX_DEAL_DURATION_SECONDS, interval: MAX_DEAL_DURATION_SECONDS / 8, ..Terms::default() };
    assert_ok(env.create_with(2, longest));
}

#[test]
fn create_rejects_invalid_check_interval() {
    let mut env = Env::new();
    for interval in [0, 7, DURATION + 1] {
        assert_err(env.create_with(1, Terms { interval, ..Terms::default() }), DealError::InvalidCheckInterval);
    }
    let too_many_rounds = Terms { duration: MAX_ROUNDS + 1, interval: 1, ..Terms::default() };
    assert_err(env.create_with(1, too_many_rounds), DealError::InvalidCheckInterval);
    assert!(!env.exists(&env.pda(1)));

    // The most rounds allowed fit in the account.
    assert_ok(env.create_with(1, Terms { duration: MAX_ROUNDS, interval: 1, ..Terms::default() }));
    assert_eq!(env.deal(&env.pda(1)).recorded.len(), (MAX_ROUNDS / 8) as usize);
}

#[test]
fn create_rejects_invalid_threshold() {
    let mut env = Env::new();
    for min_bps in [0, 10_001] {
        assert_err(env.create_with(1, Terms { min_bps, ..Terms::default() }), DealError::InvalidThreshold);
    }
    assert_ok(env.create_with(1, Terms { min_bps: 10_000, ..Terms::default() }));
}

#[test]
fn accept_rejects_anyone_but_the_recipient() {
    let mut env = Env::new();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    let mallory = stranger(&mut env);
    let payer = env.payer.insecure_clone();
    let escrow = env.balance(&env.pda(1));
    for signer in [&mallory, &payer] {
        assert_anchor_code(env.accept_as(signer, 1), CONSTRAINT_HAS_ONE);
    }
    assert_eq!(env.balance(&env.pda(1)), escrow, "no guarantee was locked");
    assert_eq!(env.deal(&env.pda(1)).status, uptime_deal::state::DealStatus::AwaitingProvider);
}

#[test]
fn accept_rejects_an_active_deal() {
    let mut env = Env::new();
    env.svm.airdrop(&env.recipient.pubkey(), 3 * SOL).unwrap();
    assert_ok(env.create(1)); // no guarantee: already active
    assert_err(env.accept(1), DealError::DealAlreadyActive);

    assert_ok(env.create_with(2, Terms { stake: SOL, ..Terms::default() }));
    assert_ok(env.accept(2));
    let before = env.balance(&env.recipient.pubkey());
    assert_err(env.accept(2), DealError::DealAlreadyActive);
    assert!(before - env.balance(&env.recipient.pubkey()) < SOL, "the guarantee isn't taken twice");
}

#[test]
fn accept_fails_when_recipient_cannot_fund() {
    let mut env = Env::new();
    env.svm.airdrop(&env.recipient.pubkey(), SOL / 10).unwrap();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    assert!(env.accept(1).is_err());
    assert!(env.cancel(1).is_ok(), "still awaiting the provider");
}

#[test]
fn observe_rejects_unauthorized_signers() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.set_time(T0 + DURATION as i64);
    let mallory = stranger(&mut env);
    let recipient = env.recipient.insecure_clone();
    env.svm.airdrop(&recipient.pubkey(), SOL).unwrap();
    let payer = env.payer.insecure_clone();

    // Neither a stranger nor either party can feed the counters.
    for signer in [&mallory, &recipient, &payer] {
        assert_err(env.observe_as(signer, 1, 0, true), DealError::UnauthorizedOracle);
        assert_err(env.observe_as(signer, 1, 1, false), DealError::UnauthorizedOracle);
    }
    let stored = env.deal(&env.pda(1));
    assert_eq!((stored.up_checks, stored.down_checks), (0, 0));
}

#[test]
fn observe_rejects_a_duplicate_round() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.set_time(T0 + DURATION as i64);
    assert_ok(env.observe(1, 4, true));

    // Neither the same report nor a contradicting one counts again.
    assert_err(env.observe(1, 4, true), DealError::RoundAlreadyRecorded);
    assert_err(env.observe(1, 4, false), DealError::RoundAlreadyRecorded);
    let stored = env.deal(&env.pda(1));
    assert_eq!((stored.up_checks, stored.down_checks), (1, 0));
}

#[test]
fn observe_rejects_a_round_outside_the_window() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.set_time(T0 + DURATION as i64);
    for round in [ROUNDS, ROUNDS + 1, u32::MAX] {
        assert_err(env.observe(1, round, true), DealError::RoundOutOfRange);
    }
}

#[test]
fn observe_rejects_a_round_that_has_not_ended() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.set_time(T0 + INTERVAL as i64 - 1);
    assert_err(env.observe(1, 0, true), DealError::RoundNotEnded);
    env.set_time(T0 + INTERVAL as i64);
    assert_ok(env.observe(1, 0, true));
    assert_err(env.observe(1, 1, true), DealError::RoundNotEnded);
}

#[test]
fn observe_rejects_after_the_grace_period() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    let close = T0 + DURATION as i64 + OBSERVATION_GRACE_SECONDS;
    env.set_time(close - 1);
    assert_ok(env.observe(1, 0, true));
    env.set_time(close);
    assert_err(env.observe(1, 1, true), DealError::ObservationsClosed);
}

#[test]
fn observe_rejects_a_deal_awaiting_the_provider() {
    let mut env = Env::new();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    env.set_time(T0 + DURATION as i64);
    assert_err(env.observe(1, 0, true), DealError::DealNotActive);
}

#[test]
fn settle_rejects_before_the_window_and_grace_end() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.observe_all(1, &pattern(10, 0, 0));
    let end = T0 + DURATION as i64;
    for now in [T0, end - 1, end, end + OBSERVATION_GRACE_SECONDS - 1] {
        env.set_time(now);
        assert_err(env.settle(1), DealError::SettleTooEarly);
    }
    assert_eq!(env.balance(&env.recipient.pubkey()), 0);
    assert!(env.exists(&env.pda(1)));
}

#[test]
fn settle_rejects_a_deal_awaiting_the_provider() {
    let mut env = Env::new();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    env.set_time(T0 + 10 * DURATION as i64);
    assert_err(env.settle(1), DealError::DealNotActive);
}

#[test]
fn settle_rejects_swapped_or_foreign_wallets() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.observe_all(1, &pattern(10, 0, 0));
    env.to_settlement(1);
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.insecure_clone());
    let deal = env.pda(1);
    let thief = Keypair::new().pubkey();

    for (p, r) in [(payer, thief), (thief, recipient), (recipient, payer)] {
        let ix = Env::settle_ix(&oracle.pubkey(), &deal, &p, &r);
        assert_anchor_code(env.send(&[ix], &oracle, &[]), CONSTRAINT_HAS_ONE);
    }
    assert_eq!(env.balance(&thief), 0);
    assert_eq!(env.deal(&deal).amount_lamports, AMOUNT);
}

#[test]
fn settle_unknown_deal_fails() {
    let mut env = Env::new();
    assert_anchor_code(env.settle(42), ACCOUNT_NOT_INITIALIZED);
}

#[test]
fn cancel_rejects_an_active_deal() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    for now in [T0, T0 + 10 * DURATION as i64] {
        env.set_time(now);
        assert_err(env.cancel(1), DealError::DealAlreadyActive);
    }
    assert_eq!(env.deal(&env.pda(1)).amount_lamports, AMOUNT);
}

#[test]
fn cancel_rejects_anyone_but_the_payer() {
    let mut env = Env::new();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    let mallory = stranger(&mut env);
    let recipient = env.recipient.insecure_clone();
    env.svm.airdrop(&recipient.pubkey(), SOL).unwrap();
    let oracle = env.oracle.insecure_clone();
    for signer in [&mallory, &recipient, &oracle] {
        let before = env.balance(&signer.pubkey());
        assert_anchor_code(env.cancel_as(signer, 1), CONSTRAINT_HAS_ONE);
        assert!(env.balance(&signer.pubkey()) <= before, "the signer only pays the fee");
    }
    assert_eq!(env.deal(&env.pda(1)).amount_lamports, AMOUNT);
}

#[test]
fn cancel_requires_payer_signature() {
    let mut env = Env::new();
    assert_ok(env.create_with(1, Terms { stake: SOL, ..Terms::default() }));
    let oracle = env.oracle.insecure_clone();
    let mut ix = Env::cancel_ix(&env.payer.pubkey(), &env.pda(1));
    ix.accounts[0].is_signer = false; // try to close the deal without the payer's signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
    assert!(env.exists(&env.pda(1)));
}

#[test]
fn cancel_unknown_deal_fails() {
    let mut env = Env::new();
    assert_anchor_code(env.cancel(42), ACCOUNT_NOT_INITIALIZED);
}
