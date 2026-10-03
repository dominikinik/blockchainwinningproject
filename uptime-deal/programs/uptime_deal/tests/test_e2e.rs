//! End-to-end flows: the payer funds a deal, the oracle reports uptime, and the money moves.
mod common;

use common::*;
use solana_signer::Signer;
use uptime_deal::CANCEL_TIMEOUT_SECONDS;

const FEE: u64 = 5_000;

/// Full flow with uptime above 99%: the recipient ends up with exactly the escrow, the payer
/// loses exactly the escrow plus one fee (the rent comes back), and the deal is closed.
#[test]
fn uptime_above_99_pays_recipient() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    let deal = deal_pda(&payer, 1);
    let payer_start = env.balance(&payer);
    assert_eq!(env.balance(&recipient), 0);

    env.set_time(1_700_000_000);
    assert_ok(env.create(1, AMOUNT));
    let stored = env.deal(&deal);
    assert_eq!(
        (stored.payer, stored.recipient, stored.oracle, stored.deal_id, stored.amount_lamports),
        (payer, recipient, env.oracle.pubkey(), 1, AMOUNT)
    );
    // The window is fixed on chain when the deal is created.
    assert_eq!((stored.starts_at, stored.duration_seconds), (1_700_000_000, DURATION));
    let rent = env.balance(&deal) - AMOUNT;
    assert!(rent > 0);
    assert_eq!(env.balance(&payer), payer_start - AMOUNT - rent - FEE);

    // A day of per-second history with 10 minutes of downtime: 99.31% uptime.
    let meta = assert_ok(env.settle(1, 86_400 - 600, 86_400));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealSettled event emitted");

    assert_eq!(env.balance(&recipient), AMOUNT);
    assert_eq!(env.balance(&payer), payer_start - AMOUNT - FEE);
    assert!(!env.exists(&deal), "deal is closed");
}

/// Full flow with uptime at or below 99%: the payer gets everything back except the fee and
/// the recipient receives nothing.
#[test]
fn uptime_at_or_below_99_refunds_payer() {
    for (deal_id, up, total) in [(1, 99, 100), (2, 980, 1000), (3, 0, 3600)] {
        let mut env = Env::new();
        let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
        let payer_start = env.balance(&payer);

        assert_ok(env.create(deal_id, AMOUNT));
        assert_ok(env.settle(deal_id, up, total));

        assert_eq!(env.balance(&recipient), 0, "{up}/{total}");
        assert_eq!(env.balance(&payer), payer_start - FEE, "{up}/{total}");
        assert!(!env.exists(&deal_pda(&payer, deal_id)));
    }
}

/// The boundary is strict: 99.01% pays, 99.00% refunds.
#[test]
fn threshold_boundary() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    assert_ok(env.create(1, AMOUNT));
    assert_ok(env.create(2, AMOUNT));

    assert_ok(env.settle(1, 9_900, 10_000));
    assert_eq!(env.balance(&recipient), 0);

    assert_ok(env.settle(2, 9_901, 10_000));
    assert_eq!(env.balance(&recipient), AMOUNT);
}

/// Several deals between the same parties settle independently, and no lamports are
/// created or lost across the whole flow.
#[test]
fn independent_deals_conserve_lamports() {
    let mut env = Env::new();
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.pubkey());
    let total_start = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);

    assert_ok(env.create(7, AMOUNT));
    assert_ok(env.create(8, 3 * AMOUNT));
    assert_ok(env.settle(8, 100, 100)); // pays 3 * AMOUNT
    assert_ok(env.settle(7, 50, 100)); // refunds AMOUNT

    assert_eq!(env.balance(&recipient), 3 * AMOUNT);
    let total_end = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);
    assert_eq!(total_start - total_end, 4 * FEE, "only fees leave the three wallets");
}

/// A settled deal is closed, so it can't be settled again, and its id can be reused.
#[test]
fn settled_deal_cannot_be_settled_twice_and_id_is_reusable() {
    let mut env = Env::new();
    assert_ok(env.create(1, AMOUNT));
    assert_ok(env.settle(1, 100, 100));

    // AccountNotInitialized (3012): the deal account no longer exists.
    assert_anchor_code(env.settle(1, 100, 100), 3012);
    assert_eq!(env.balance(&env.recipient.pubkey()), AMOUNT);

    assert_ok(env.create(1, AMOUNT));
    assert_ok(env.settle(1, 100, 100));
    assert_eq!(env.balance(&env.recipient.pubkey()), 2 * AMOUNT);
}

/// The oracle never settles: once the window and the timeout have passed, the payer cancels
/// and gets everything back except two fees, and the deal can no longer be settled.
#[test]
fn payer_cancels_unsettled_deal_after_timeout() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    let deal = deal_pda(&payer, 1);
    let payer_start = env.balance(&payer);

    env.set_time(1_000_000);
    assert_ok(env.create(1, AMOUNT));
    env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS);
    let meta = assert_ok(env.cancel(1));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealCancelled event emitted");

    assert_eq!(env.balance(&payer), payer_start - 2 * FEE);
    assert_eq!(env.balance(&recipient), 0);
    assert!(!env.exists(&deal), "deal is closed");
    // AccountNotInitialized (3012): a late settlement finds nothing to pay out.
    assert_anchor_code(env.settle(1, 100, 100), 3012);
}

/// After the timeout the oracle may still settle; whichever lands first closes the deal.
#[test]
fn late_settlement_wins_over_cancel() {
    let mut env = Env::new();
    env.set_time(1_000_000);
    assert_ok(env.create(1, AMOUNT));
    env.set_time(1_000_000 + DURATION as i64 + CANCEL_TIMEOUT_SECONDS + 5);

    assert_ok(env.settle(1, 100, 100));
    assert_eq!(env.balance(&env.recipient.pubkey()), AMOUNT);
    assert_anchor_code(env.cancel(1), 3012);
}
