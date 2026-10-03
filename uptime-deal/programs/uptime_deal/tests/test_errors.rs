//! Every rejected `create_deal`, `accept_deal`, `settle_deal` and `cancel_deal`, and that a rejection
//! moves no lamports.
mod common;

use common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use uptime_deal::{
    error::DealError, state::DealStatus, ACCEPT_TIMEOUT_SECONDS, CANCEL_TIMEOUT_SECONDS, MAX_DEAL_DURATION_SECONDS,
    MIN_DEAL_LAMPORTS,
};

// Anchor error codes.
const ACCOUNT_ALREADY_IN_USE: u32 = 0; // system program: account already exists
const CONSTRAINT_HAS_ONE: u32 = 2001;
const ACCOUNT_NOT_INITIALIZED: u32 = 3012;

#[test]
fn create_rejects_amount_below_minimum() {
    let mut env = Env::new();
    assert_err(env.propose(1, 0), DealError::AmountTooSmall);
    assert_err(env.propose(1, MIN_DEAL_LAMPORTS - 1), DealError::AmountTooSmall);
    assert!(!env.exists(&deal_pda(&env.payer.pubkey(), 1)));

    // The minimum itself is accepted and can be paid out.
    let before = env.balance(&env.recipient.pubkey());
    env.open(1, MIN_DEAL_LAMPORTS);
    assert_ok(env.settle(1, 1, 1));
    assert_eq!(env.balance(&env.recipient.pubkey()), before + MIN_DEAL_LAMPORTS - 5_000);
}

#[test]
fn create_rejects_guarantee_below_minimum() {
    let mut env = Env::new();
    assert_err(env.create_for(1, AMOUNT, 0, DURATION), DealError::GuaranteeTooSmall);
    assert_err(env.create_for(1, AMOUNT, MIN_DEAL_LAMPORTS - 1, DURATION), DealError::GuaranteeTooSmall);
    assert!(!env.exists(&deal_pda(&env.payer.pubkey(), 1)));

    assert_ok(env.create_for(1, AMOUNT, MIN_DEAL_LAMPORTS, DURATION));
    assert_ok(env.accept(1));
}

#[test]
fn create_rejects_payer_as_recipient() {
    let mut env = Env::new();
    let payer = env.payer.insecure_clone();
    let ix = Env::create_ix(&payer.pubkey(), &payer.pubkey(), &env.oracle.pubkey(), 1, AMOUNT, GUARANTEE, DURATION);
    assert_err(env.send(&[ix], &payer, &[]), DealError::RecipientIsPayer);
}

#[test]
fn create_rejects_duplicate_deal_id() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    assert_anchor_code(env.propose(1, AMOUNT), ACCOUNT_ALREADY_IN_USE);
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).amount_lamports, AMOUNT);
}

#[test]
fn create_fails_when_payer_cannot_fund() {
    let mut env = Env::new();
    let poor = Keypair::new();
    env.svm.airdrop(&poor.pubkey(), SOL).unwrap();
    let ix = Env::create_ix(&poor.pubkey(), &env.recipient.pubkey(), &env.oracle.pubkey(), 1, 5 * SOL, GUARANTEE, DURATION);
    assert!(env.send(&[ix], &poor, &[]).is_err());
    assert!(!env.exists(&deal_pda(&poor.pubkey(), 1)));
}

#[test]
fn create_requires_payer_signature() {
    let mut env = Env::new();
    let oracle = env.oracle.insecure_clone();
    let mut ix = Env::create_ix(&env.payer.pubkey(), &env.recipient.pubkey(), &oracle.pubkey(), 1, AMOUNT, GUARANTEE, DURATION);
    ix.accounts[0].is_signer = false; // try to spend the payer's lamports without its signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
}

#[test]
fn create_rejects_invalid_duration() {
    let mut env = Env::new();
    assert_err(env.create_for(1, AMOUNT, GUARANTEE, 0), DealError::InvalidDuration);
    assert_err(env.create_for(1, AMOUNT, GUARANTEE, MAX_DEAL_DURATION_SECONDS + 1), DealError::InvalidDuration);
    assert!(!env.exists(&deal_pda(&env.payer.pubkey(), 1)));

    assert_ok(env.create_for(1, AMOUNT, GUARANTEE, 1));
    assert_ok(env.create_for(2, AMOUNT, GUARANTEE, MAX_DEAL_DURATION_SECONDS));
}

#[test]
fn accept_rejects_anyone_but_the_recipient() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    let terms = env.terms(AMOUNT);
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), 10 * SOL).unwrap();
    let (payer, oracle) = (env.payer.insecure_clone(), env.oracle.insecure_clone());

    for signer in [&mallory, &payer, &oracle] {
        assert_anchor_code(env.accept_as(signer, 1, terms), CONSTRAINT_HAS_ONE);
    }
    let deal = env.deal(&deal_pda(&env.payer.pubkey(), 1));
    assert_eq!((deal.status, deal.starts_at), (DealStatus::Proposed, 0));
}

#[test]
fn accept_requires_recipient_signature() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    let oracle = env.oracle.insecure_clone();
    let mut ix = Env::accept_ix(&env.recipient.pubkey(), &deal_pda(&env.payer.pubkey(), 1), env.terms(AMOUNT));
    ix.accounts[0].is_signer = false; // try to take the recipient's guarantee without its signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).status, DealStatus::Proposed);
}

#[test]
fn accept_rejects_an_accepted_deal() {
    let mut env = Env::new();
    env.open(1, AMOUNT);
    let deal = deal_pda(&env.payer.pubkey(), 1);
    let locked = env.balance(&deal);
    assert_err(env.accept(1), DealError::DealNotProposed);
    assert_eq!(env.balance(&deal), locked, "the guarantee is taken only once");
}

#[test]
fn accept_closes_at_the_deadline() {
    let mut env = Env::new();
    env.set_time(1_000_000);
    assert_ok(env.propose(1, AMOUNT));
    assert_ok(env.propose(2, AMOUNT));

    env.set_time(1_000_000 + ACCEPT_TIMEOUT_SECONDS);
    assert_err(env.accept(1), DealError::AcceptExpired);
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).status, DealStatus::Proposed);
    // The payer can still withdraw an expired proposal.
    assert_ok(env.cancel(1));

    env.set_time(1_000_000 + ACCEPT_TIMEOUT_SECONDS - 1);
    assert_ok(env.accept(2));
}

#[test]
fn accept_rejects_terms_that_differ_from_the_deal() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    let deal = deal_pda(&env.payer.pubkey(), 1);
    let locked = env.balance(&deal);
    let good = env.terms(AMOUNT);
    let recipient = env.recipient.insecure_clone();

    for bad in [
        Terms { amount: AMOUNT + 1, ..good },
        Terms { guarantee: GUARANTEE - 1, ..good },
        Terms { duration: DURATION + 1, ..good },
        Terms { oracle: Keypair::new().pubkey(), ..good },
    ] {
        assert_err(env.accept_as(&recipient, 1, bad), DealError::TermsMismatch);
    }
    assert_eq!(env.balance(&deal), locked);
    assert_ok(env.accept_as(&recipient, 1, good));
}

/// The payer replaces a proposal at the same address with harsher terms while the recipient's
/// acceptance of the original is in flight: the acceptance fails instead of binding the recipient.
#[test]
fn accept_rejects_a_proposal_replaced_at_the_same_address() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    let seen = env.terms(AMOUNT);

    assert_ok(env.cancel(1));
    assert_ok(env.create_for(1, AMOUNT, 2 * GUARANTEE, DURATION));

    let recipient = env.recipient.insecure_clone();
    let before = env.balance(&recipient.pubkey());
    assert_err(env.accept_as(&recipient, 1, seen), DealError::TermsMismatch);
    assert!(env.balance(&recipient.pubkey()) >= before - 5_000, "only the fee left the recipient");
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).status, DealStatus::Proposed);
}

#[test]
fn accept_fails_when_recipient_cannot_fund() {
    let mut env = Env::new();
    assert_ok(env.create_for(1, AMOUNT, 20 * SOL, DURATION)); // the recipient holds 10 SOL
    assert!(env.accept(1).is_err());
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).status, DealStatus::Proposed);
}

#[test]
fn accept_unknown_deal_fails() {
    let mut env = Env::new();
    let terms = env.terms(AMOUNT);
    let recipient = env.recipient.insecure_clone();
    assert_anchor_code(env.accept_as(&recipient, 42, terms), ACCOUNT_NOT_INITIALIZED);
}

#[test]
fn settle_rejects_a_proposal() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    assert_err(env.settle(1, 100, 100), DealError::DealNotActive);
    assert_eq!(env.deal(&deal_pda(&env.payer.pubkey(), 1)).status, DealStatus::Proposed);
    assert!(env.balance(&deal_pda(&env.payer.pubkey(), 1)) > AMOUNT, "the payment stays locked");
}

#[test]
fn settle_rejects_wrong_oracle() {
    let mut env = Env::new();
    env.open(1, AMOUNT);
    let deal = deal_pda(&env.payer.pubkey(), 1);
    let locked = env.balance(&deal);
    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    assert_err(env.settle_as(&mallory, 1, 100, 100), DealError::UnauthorizedOracle);

    // Neither party can settle on the oracle's behalf.
    let recipient = env.recipient.insecure_clone();
    assert_err(env.settle_as(&recipient, 1, 100, 100), DealError::UnauthorizedOracle);
    let payer = env.payer.insecure_clone();
    assert_err(env.settle_as(&payer, 1, 0, 100), DealError::UnauthorizedOracle);

    assert_eq!(env.balance(&deal), locked);
}

#[test]
fn settle_rejects_invalid_uptime() {
    let mut env = Env::new();
    env.open(1, AMOUNT);
    assert_err(env.settle(1, 0, 0), DealError::InvalidUptime);
    assert_err(env.settle(1, 101, 100), DealError::InvalidUptime);
    assert!(env.exists(&deal_pda(&env.payer.pubkey(), 1)), "deal stays open");
}

#[test]
fn settle_rejects_swapped_or_foreign_wallets() {
    let mut env = Env::new();
    env.open(1, AMOUNT);
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.insecure_clone());
    let deal = deal_pda(&payer, 1);
    let locked = env.balance(&deal);
    let thief = Keypair::new().pubkey();

    for (p, r) in [(payer, thief), (thief, recipient), (recipient, payer)] {
        let ix = Env::settle_ix(&oracle.pubkey(), &deal, &p, &r, 100, 100);
        assert_anchor_code(env.send(&[ix], &oracle, &[]), CONSTRAINT_HAS_ONE);
    }
    assert_eq!(env.balance(&thief), 0);
    assert_eq!(env.balance(&deal), locked);
}

#[test]
fn settle_unknown_deal_fails() {
    let mut env = Env::new();
    assert_anchor_code(env.settle(42, 100, 100), ACCOUNT_NOT_INITIALIZED);
}

/// The cancel timeout counts from acceptance: a deal accepted late can't be cancelled at a time that
/// would have been late enough had the window started at the proposal.
#[test]
fn cancel_rejects_an_accepted_deal_before_timeout() {
    let mut env = Env::new();
    env.set_time(1_000_000);
    assert_ok(env.propose(1, AMOUNT));
    env.set_time(1_000_500);
    assert_ok(env.accept(1));
    let deal = deal_pda(&env.payer.pubkey(), 1);
    let locked = env.balance(&deal);
    let window_end = 1_000_500 + DURATION as i64;
    let recipient = env.recipient.insecure_clone();

    for now in [1_000_500, window_end, 1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS, window_end + CANCEL_TIMEOUT_SECONDS - 1] {
        env.set_time(now);
        assert_err(env.cancel(1), DealError::CancelTooEarly);
        assert_err(env.cancel_as(&recipient, 1), DealError::CancelTooEarly);
    }
    assert_eq!(env.balance(&deal), locked);
}

#[test]
fn cancel_rejects_anyone_but_the_parties() {
    let mut env = Env::new();
    env.set_time(1_000_000);
    assert_ok(env.propose(1, AMOUNT));
    env.open(2, AMOUNT);
    env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS);

    let mallory = Keypair::new();
    env.svm.airdrop(&mallory.pubkey(), SOL).unwrap();
    let oracle = env.oracle.insecure_clone();
    for deal_id in [1, 2] {
        for signer in [&mallory, &oracle] {
            assert_err(env.cancel_as(signer, deal_id), DealError::NotAParty);
        }
        assert!(env.exists(&deal_pda(&env.payer.pubkey(), deal_id)));
    }
}

#[test]
fn cancel_rejects_swapped_or_foreign_wallets() {
    let mut env = Env::new();
    env.set_time(1_000_000);
    env.open(1, AMOUNT);
    env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS);
    let (payer, recipient) = (env.payer.insecure_clone(), env.recipient.pubkey());
    let deal = deal_pda(&payer.pubkey(), 1);
    let locked = env.balance(&deal);
    let thief = Keypair::new().pubkey();

    for (p, r) in [(payer.pubkey(), thief), (thief, recipient), (recipient, payer.pubkey())] {
        let ix = Env::cancel_ix(&payer.pubkey(), &deal, &p, &r);
        assert_anchor_code(env.send(&[ix], &payer, &[]), CONSTRAINT_HAS_ONE);
    }
    assert_eq!(env.balance(&thief), 0);
    assert_eq!(env.balance(&deal), locked);
}

#[test]
fn cancel_requires_a_signature() {
    let mut env = Env::new();
    assert_ok(env.propose(1, AMOUNT));
    let oracle = env.oracle.insecure_clone();
    let deal = deal_pda(&env.payer.pubkey(), 1);
    let mut ix = Env::cancel_ix(&env.payer.pubkey(), &deal, &env.payer.pubkey(), &env.recipient.pubkey());
    ix.accounts[0].is_signer = false; // try to close the deal without the payer's signature
    assert!(env.send(&[ix], &oracle, &[]).is_err());
    assert!(env.exists(&deal));
}

#[test]
fn cancel_unknown_deal_fails() {
    let mut env = Env::new();
    assert_anchor_code(env.cancel(42), ACCOUNT_NOT_INITIALIZED);
}
