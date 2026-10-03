//! Every rejected `create_deal` and `settle_deal`, and that a rejection moves no lamports.
mod common;

use common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use uptime_deal::{error::DealError, MIN_DEAL_LAMPORTS};

// Anchor error codes.
const ACCOUNT_ALREADY_IN_USE: u32 = 0; // system program: account already exists
const CONSTRAINT_HAS_ONE: u32 = 2001;

#[test]
fn create_rejects_amount_below_minimum() {
    let mut env = Env::new();
    assert_err(env.create(1, 0), DealError::AmountTooSmall);
    assert_err(env.create(1, MIN_DEAL_LAMPORTS - 1), DealError::AmountTooSmall);
    assert!(!env.exists(&deal_pda(&env.payer.pubkey(), 1)));

    // The minimum itself is accepted, and paying it to a brand-new wallet works.
    assert_ok(env.create(1, MIN_DEAL_LAMPORTS));
    assert_ok(env.settle(1, 1, 1));
    assert_eq!(env.balance(&env.recipient.pubkey()), MIN_DEAL_LAMPORTS);
}

#[test]
fn create_rejects_payer_as_recipient() {
    let mut env = Env::new();
    let payer = env.payer.insecure_clone();
    let ix = Env::create_ix(&payer.pubkey(), &payer.pubkey(), &env.oracle.pubkey(), 1, AMOUNT);
    assert_err(env.send(&[ix], &payer, &[]), DealError::RecipientIsPayer);
}

#[test]
fn create_rejects_duplicate_deal_id() {
    let mut env = Env::new();
    assert_ok(env.create(1, AMOUNT));
    assert_anchor_code(env.create(1, AMOUNT), ACCOUNT_ALREADY_IN_USE);
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).amount_lamports, AMOUNT);
}

#[test]
fn create_fails_when_payer_cannot_fund() {
    let mut env = Env::new();
    let poor = Keypair::new();
    env.svm.airdrop(&poor.pubkey(), SOL).unwrap();
    let ix = Env::create_ix(&poor.pubkey(), &env.recipient.pubkey(), &env.oracle.pubkey(), 1, 5 * SOL);
    assert!(env.send(&[ix], &poor, &[]).is_err());
    assert!(!env.exists(&deal_pda(&poor.pubkey(), 1)));
}

#[test]
fn create_requires_payer_signature() {
    let mut env = Env::new();
    let oracle = env.oracle.insecure_clone();
    let mut ix = Env::create_ix(&env.payer.pubkey(), &env.recipient.pubkey(), &oracle.pubkey(), 1, AMOUNT);
    ix.accounts[0].is_signer = false; // try to spend the payer's lamports without its signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
}

#[test]
fn settle_rejects_wrong_oracle() {
    let mut env = Env::new();
    assert_ok(env.create(1, AMOUNT));
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    assert_err(env.settle_as(&mallory, 1, 100, 100), DealError::UnauthorizedOracle);

    // Neither party can settle on the oracle's behalf.
    let recipient = env.recipient.insecure_clone();
    env.svm.airdrop(&recipient.pubkey(), SOL).unwrap();
    assert_err(env.settle_as(&recipient, 1, 100, 100), DealError::UnauthorizedOracle);
    let payer = env.payer.insecure_clone();
    assert_err(env.settle_as(&payer, 1, 0, 100), DealError::UnauthorizedOracle);

    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).amount_lamports, AMOUNT);
}

#[test]
fn settle_rejects_invalid_uptime() {
    let mut env = Env::new();
    assert_ok(env.create(1, AMOUNT));
    assert_err(env.settle(1, 0, 0), DealError::InvalidUptime);
    assert_err(env.settle(1, 101, 100), DealError::InvalidUptime);
    assert!(env.exists(&deal_pda(&env.payer.pubkey(), 1)), "deal stays open");
}

#[test]
fn settle_rejects_swapped_or_foreign_wallets() {
    let mut env = Env::new();
    assert_ok(env.create(1, AMOUNT));
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.insecure_clone());
    let deal = deal_pda(&payer, 1);
    let thief = Keypair::new().pubkey();

    for (p, r) in [(payer, thief), (thief, recipient), (recipient, payer)] {
        let ix = Env::settle_ix(&oracle.pubkey(), &deal, &p, &r, 100, 100);
        assert_anchor_code(env.send(&[ix], &oracle, &[]), CONSTRAINT_HAS_ONE);
    }
    assert_eq!(env.balance(&thief), 0);
    assert_eq!(env.deal(&deal).amount_lamports, AMOUNT);
}

#[test]
fn settle_unknown_deal_fails() {
    let mut env = Env::new();
    // AccountNotInitialized (3012).
    assert_anchor_code(env.settle(42, 100, 100), 3012);
}
