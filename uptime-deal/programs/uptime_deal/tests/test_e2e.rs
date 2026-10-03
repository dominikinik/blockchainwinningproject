//! End-to-end flows: the payer proposes a deal, the recipient accepts it, the oracle reports uptime,
//! and the money moves.
mod common;

use common::*;
use solana_signer::Signer;
use uptime_deal::{state::DealStatus, ACCEPT_TIMEOUT_SECONDS, CANCEL_TIMEOUT_SECONDS};

const FEE: u64 = 5_000;

/// Full flow with uptime above 99%: the proposal holds only the payment, acceptance adds the guarantee
/// and starts the window, and settlement pays both deposits to the recipient. Each party loses only
/// its fees on top of what it gave or got.
#[test]
fn uptime_above_99_pays_recipient_both_deposits() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    let deal = deal_pda(&payer, 1);
    let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

    env.set_time(1_700_000_000);
    assert_ok(env.propose(1, AMOUNT));
    let stored = env.deal(&deal);
    assert_eq!(
        (stored.payer, stored.recipient, stored.oracle, stored.deal_id, stored.amount_lamports, stored.guarantee_lamports),
        (payer, recipient, env.oracle.pubkey(), 1, AMOUNT, GUARANTEE)
    );
    assert_eq!(stored.status, DealStatus::Proposed);
    assert_eq!((stored.starts_at, stored.duration_seconds), (0, DURATION), "no window runs before acceptance");
    assert_eq!(stored.accept_deadline, 1_700_000_000 + ACCEPT_TIMEOUT_SECONDS);
    let rent = env.balance(&deal) - AMOUNT;
    assert!(rent > 0);
    assert_eq!(env.balance(&payer), payer_start - AMOUNT - rent - FEE);
    assert_eq!(env.balance(&recipient), recipient_start, "a proposal takes nothing from the recipient");

    // The window starts when the recipient accepts, not when the payer proposed.
    env.set_time(1_700_000_500);
    let meta = assert_ok(env.accept(1));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealAccepted event emitted");
    let stored = env.deal(&deal);
    assert_eq!((stored.status, stored.starts_at), (DealStatus::Active, 1_700_000_500));
    assert_eq!(env.balance(&deal), AMOUNT + GUARANTEE + rent);
    assert_eq!(env.balance(&recipient), recipient_start - GUARANTEE - FEE);

    // A day of per-second history with 10 minutes of downtime: 99.31% uptime.
    let meta = assert_ok(env.settle(1, 86_400 - 600, 86_400));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealSettled event emitted");

    assert_eq!(env.balance(&recipient), recipient_start + AMOUNT - FEE);
    assert_eq!(env.balance(&payer), payer_start - AMOUNT - FEE);
    assert!(!env.exists(&deal), "deal is closed");
}

/// Full flow with uptime at or below 99%: the payer gets its payment back plus the recipient's
/// guarantee, and the recipient loses the guarantee.
#[test]
fn uptime_at_or_below_99_pays_payer_both_deposits() {
    for (deal_id, up, total) in [(1, 99, 100), (2, 980, 1000), (3, 0, 3600)] {
        let mut env = Env::new();
        let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
        let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

        env.open(deal_id, AMOUNT);
        assert_ok(env.settle(deal_id, up, total));

        assert_eq!(env.balance(&payer), payer_start + GUARANTEE - FEE, "{up}/{total}");
        assert_eq!(env.balance(&recipient), recipient_start - GUARANTEE - FEE, "{up}/{total}");
        assert!(!env.exists(&deal_pda(&payer, deal_id)));
    }
}

/// The boundary is strict: 99.01% pays the recipient, 99.00% pays the payer.
#[test]
fn threshold_boundary() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    env.open(1, AMOUNT);
    env.open(2, AMOUNT);
    let before = env.balance(&recipient);

    assert_ok(env.settle(1, 9_900, 10_000));
    assert_eq!(env.balance(&recipient), before);

    assert_ok(env.settle(2, 9_901, 10_000));
    assert_eq!(env.balance(&recipient), before + AMOUNT + GUARANTEE);
}

/// Several deals between the same parties settle independently, and no lamports are created or lost
/// across the whole flow.
#[test]
fn independent_deals_conserve_lamports() {
    let mut env = Env::new();
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.pubkey());
    let recipient_start = env.balance(&recipient);
    let total_start = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);

    env.open(7, AMOUNT);
    env.open(8, 3 * AMOUNT);
    assert_ok(env.settle(8, 100, 100)); // pays 3 * AMOUNT + GUARANTEE to the recipient
    assert_ok(env.settle(7, 50, 100)); // pays AMOUNT + GUARANTEE to the payer

    assert_eq!(env.balance(&recipient), recipient_start + 3 * AMOUNT - GUARANTEE - 2 * FEE);
    let total_end = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);
    assert_eq!(total_start - total_end, 6 * FEE, "only fees leave the three wallets");
}

/// A settled deal is closed, so it can't be settled again, and its id can be reused.
#[test]
fn settled_deal_cannot_be_settled_twice_and_id_is_reusable() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    env.open(1, AMOUNT);
    assert_ok(env.settle(1, 100, 100));
    let after_first = env.balance(&recipient);

    // AccountNotInitialized (3012): the deal account no longer exists.
    assert_anchor_code(env.settle(1, 100, 100), 3012);
    assert_eq!(env.balance(&recipient), after_first);

    env.open(1, AMOUNT);
    assert_ok(env.settle(1, 100, 100));
    assert_eq!(env.balance(&recipient), after_first + AMOUNT - FEE);
}

/// The payer withdraws a proposal before anyone accepted it: it gets its payment and the rent back,
/// and the recipient can no longer accept.
#[test]
fn payer_withdraws_a_proposal() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

    assert_ok(env.propose(1, AMOUNT));
    let meta = assert_ok(env.cancel(1));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealCancelled event emitted");

    assert_eq!(env.balance(&payer), payer_start - 2 * FEE);
    assert_eq!(env.balance(&recipient), recipient_start);
    assert!(!env.exists(&deal_pda(&payer, 1)));
    let terms = env.terms(AMOUNT);
    let recipient_key = env.recipient.insecure_clone();
    assert_anchor_code(env.accept_as(&recipient_key, 1, terms), 3012);
}

/// The recipient rejects a proposal: the payer gets its payment and the rent back.
#[test]
fn recipient_rejects_a_proposal() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

    assert_ok(env.propose(1, AMOUNT));
    let recipient_key = env.recipient.insecure_clone();
    assert_ok(env.cancel_as(&recipient_key, 1));

    assert_eq!(env.balance(&payer), payer_start - FEE);
    assert_eq!(env.balance(&recipient), recipient_start - FEE);
    assert!(!env.exists(&deal_pda(&payer, 1)));
}

/// The oracle never settles an accepted deal: once the window and the timeout have passed, either
/// party cancels it, each deposit goes back to the party that paid it, and the deal can no longer be
/// settled.
#[test]
fn either_party_cancels_an_unsettled_deal_after_timeout() {
    for by_payer in [true, false] {
        let mut env = Env::new();
        let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
        let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

        env.set_time(1_000_000);
        env.open(1, AMOUNT);
        env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS);
        let signer = if by_payer { env.payer.insecure_clone() } else { env.recipient.insecure_clone() };
        assert_ok(env.cancel_as(&signer, 1));

        let (payer_fees, recipient_fees) = if by_payer { (2 * FEE, FEE) } else { (FEE, 2 * FEE) };
        assert_eq!(env.balance(&payer), payer_start - payer_fees, "by payer: {by_payer}");
        assert_eq!(env.balance(&recipient), recipient_start - recipient_fees, "by payer: {by_payer}");
        assert!(!env.exists(&deal_pda(&payer, 1)), "deal is closed");
        // AccountNotInitialized (3012): a late settlement finds nothing to pay out.
        assert_anchor_code(env.settle(1, 100, 100), 3012);
    }
}

/// After the timeout the oracle may still settle; whichever lands first closes the deal.
#[test]
fn late_settlement_wins_over_cancel() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    env.set_time(1_000_000);
    env.open(1, AMOUNT);
    let before = env.balance(&recipient);
    env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS + 5);

    assert_ok(env.settle(1, 100, 100));
    assert_eq!(env.balance(&recipient), before + AMOUNT + GUARANTEE);
    assert_anchor_code(env.cancel(1), 3012);
}
