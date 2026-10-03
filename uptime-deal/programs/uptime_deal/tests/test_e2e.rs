//! End-to-end flows: both sides lock funds, the oracle records observations, the program judges the
//! SLA from its own counters, and the whole escrow moves to the winner.
mod common;

use common::*;
use solana_keypair::Keypair;
use solana_signer::Signer;
use uptime_deal::{logic, state::DealStatus};

const FEE: u64 = 5_000;
const STAKE: u64 = SOL;

/// A deal with a provider guarantee: payment plus guarantee are locked, then returned to the winner.
fn staked() -> Terms {
    Terms { stake: STAKE, ..Terms::default() }
}

/// UP observations increment `up_checks`, set the round's bit, and touch nothing else.
#[test]
fn up_observation_updates_on_chain_state() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    let deal = env.pda(1);
    env.set_time(T0 + INTERVAL as i64);

    let meta = assert_ok(env.observe(1, 0, true));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "ObservationRecorded emitted");
    let stored = env.deal(&deal);
    assert_eq!((stored.up_checks, stored.down_checks), (1, 0));
    assert!(logic::is_recorded(&stored.recorded, 0));
    assert!(!logic::is_recorded(&stored.recorded, 1));
    assert_eq!(stored.amount_lamports, AMOUNT);
}

/// DOWN observations increment `down_checks` and set the round's bit.
#[test]
fn down_observation_updates_on_chain_state() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    let deal = env.pda(1);
    env.set_time(T0 + 3 * INTERVAL as i64);

    assert_ok(env.observe(1, 2, false));
    let stored = env.deal(&deal);
    assert_eq!((stored.up_checks, stored.down_checks), (0, 1));
    assert!(logic::is_recorded(&stored.recorded, 2));
}

/// Rounds may land in any order; each is counted once.
#[test]
fn observations_can_arrive_out_of_order() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.set_time(T0 + DURATION as i64);
    for round in [9, 0, 5, 3] {
        assert_ok(env.observe(1, round, true));
    }
    let stored = env.deal(&env.pda(1));
    assert_eq!(stored.up_checks, 4);
    assert!([0, 3, 5, 9].iter().all(|r| logic::is_recorded(&stored.recorded, *r)));
}

/// The SLA is met: the provider receives the payment and its guarantee back, the customer loses
/// exactly the payment plus its fee (the rent comes back), and the deal is closed.
#[test]
fn sla_met_pays_provider_the_whole_escrow() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    env.svm.airdrop(&recipient, 2 * SOL).unwrap();
    let deal = env.pda(1);
    let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

    assert_ok(env.create_with(1, staked()));
    let stored = env.deal(&deal);
    assert_eq!(stored.status, DealStatus::AwaitingProvider);
    assert_eq!(
        (stored.payer, stored.recipient, stored.oracle, stored.amount_lamports, stored.provider_stake_lamports),
        (payer, recipient, env.oracle.pubkey(), AMOUNT, STAKE)
    );
    assert_eq!((stored.total_rounds, stored.min_uptime_bps, stored.check_interval_seconds), (ROUNDS, MIN_BPS, INTERVAL));

    env.set_time(T0 + 30);
    assert_ok(env.accept(1));
    let stored = env.deal(&deal);
    assert_eq!(stored.status, DealStatus::Active);
    assert_eq!(stored.starts_at, T0 + 30, "the window starts when the provider accepts");
    let rent = env.balance(&deal) - AMOUNT - STAKE;

    env.observe_all(1, &pattern(9, 1, 0)); // 90% = threshold
    env.to_settlement(1);
    let meta = assert_ok(env.settle(1));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealSettled emitted");

    assert!(rent > 0);
    assert_eq!(env.balance(&recipient), recipient_start + AMOUNT - FEE);
    assert_eq!(env.balance(&payer), payer_start - AMOUNT - FEE);
    assert!(!env.exists(&deal), "deal is closed");
}

/// The SLA is breached: the customer gets its payment back plus the provider's guarantee.
#[test]
fn breach_pays_customer_the_whole_escrow() {
    let mut env = Env::new();
    let (payer, recipient) = (env.payer.pubkey(), env.recipient.pubkey());
    env.svm.airdrop(&recipient, 2 * SOL).unwrap();
    let (payer_start, recipient_start) = (env.balance(&payer), env.balance(&recipient));

    assert_ok(env.create_with(1, staked()));
    assert_ok(env.accept(1));
    env.observe_all(1, &pattern(8, 2, 0)); // 80% < 90%
    env.to_settlement(1);
    assert_ok(env.settle(1));

    assert_eq!(env.balance(&payer), payer_start + STAKE - FEE);
    assert_eq!(env.balance(&recipient), recipient_start - STAKE - FEE);
    assert!(!env.exists(&env.pda(1)));
}

/// Without a guarantee the deal starts at creation; the threshold is inclusive and exact.
#[test]
fn threshold_boundary_is_inclusive() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    assert_ok(env.create(1));
    assert_ok(env.create(2));
    assert_eq!(env.deal(&env.pda(1)).starts_at, T0);

    env.observe_all(1, &pattern(8, 2, 0));
    env.observe_all(2, &pattern(9, 1, 0));
    env.to_settlement(1);

    assert_ok(env.settle(1));
    assert_eq!(env.balance(&recipient), 0, "80% is below 90%");
    assert_ok(env.settle(2));
    assert_eq!(env.balance(&recipient), AMOUNT, "90% meets 90%");
}

/// A round the monitor never reported counts as down, so a silent monitor can't make the provider
/// win, and a deal with no observations at all goes to the customer.
#[test]
fn unobserved_rounds_count_as_down() {
    let mut env = Env::new();
    let recipient = env.recipient.pubkey();
    for (deal_id, rounds, paid) in [(1, pattern(9, 0, 1), true), (2, pattern(8, 0, 2), false), (3, pattern(0, 0, 10), false)] {
        assert_ok(env.create(deal_id));
        env.observe_all(deal_id, &rounds);
        env.to_settlement(deal_id);
        let before = env.balance(&recipient);
        assert_ok(env.settle(deal_id));
        assert_eq!(env.balance(&recipient) > before, paid, "deal {deal_id}");
        env.set_time(T0);
    }
}

/// Settlement reads nothing but the deal account: a third party with no stake and no access to any
/// database settles it, with the same result the oracle would get.
#[test]
fn anyone_can_settle_from_on_chain_state_alone() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.observe_all(1, &pattern(10, 0, 0));
    env.to_settlement(1);

    let stranger = Keypair::new();
    env.svm.airdrop(&stranger.pubkey(), SOL).unwrap();
    assert_ok(env.settle_as(&stranger, 1));
    assert_eq!(env.balance(&env.recipient.pubkey()), AMOUNT);
    assert_eq!(env.balance(&stranger.pubkey()), SOL - FEE, "the caller only pays the fee");
}

/// A settled deal is closed, so it can't be settled again, and its id can be reused.
#[test]
fn settled_deal_cannot_be_settled_twice_and_id_is_reusable() {
    let mut env = Env::new();
    assert_ok(env.create(1));
    env.observe_all(1, &pattern(10, 0, 0));
    env.to_settlement(1);
    assert_ok(env.settle(1));

    // AccountNotInitialized (3012): the deal account no longer exists.
    assert_anchor_code(env.settle(1), 3012);
    assert_eq!(env.balance(&env.recipient.pubkey()), AMOUNT);

    assert_ok(env.create(1));
    env.observe_all(1, &pattern(10, 0, 0));
    env.to_settlement(1);
    assert_ok(env.settle(1));
    assert_eq!(env.balance(&env.recipient.pubkey()), 2 * AMOUNT);
}

/// Several deals between the same parties settle independently, and no lamports are created or
/// lost across the whole flow.
#[test]
fn independent_deals_conserve_lamports() {
    let mut env = Env::new();
    let (payer, recipient, oracle) = (env.payer.pubkey(), env.recipient.pubkey(), env.oracle.pubkey());
    let total_start = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);

    assert_ok(env.create(7));
    assert_ok(env.create_with(8, Terms { amount: 3 * AMOUNT, ..Terms::default() }));
    env.observe_all(7, &pattern(5, 5, 0));
    env.observe_all(8, &pattern(10, 0, 0));
    env.to_settlement(8);
    assert_ok(env.settle(8)); // pays 3 * AMOUNT
    assert_ok(env.settle(7)); // refunds AMOUNT

    assert_eq!(env.balance(&recipient), 3 * AMOUNT);
    let total_end = env.balance(&payer) + env.balance(&recipient) + env.balance(&oracle);
    let txs = 2 + 20 + 2;
    assert_eq!(total_start - total_end, txs * FEE, "only fees leave the three wallets");
}

/// A deal the provider never accepts can be withdrawn by the payer, and then can't be accepted.
#[test]
fn payer_cancels_a_deal_the_provider_never_accepted() {
    let mut env = Env::new();
    let payer = env.payer.pubkey();
    env.svm.airdrop(&env.recipient.pubkey(), 2 * SOL).unwrap();
    let payer_start = env.balance(&payer);

    assert_ok(env.create_with(1, staked()));
    let meta = assert_ok(env.cancel(1));
    assert!(meta.logs.iter().any(|l| l.contains("Program data:")), "DealCancelled emitted");

    assert_eq!(env.balance(&payer), payer_start - 2 * FEE);
    assert!(!env.exists(&env.pda(1)));
    assert_anchor_code(env.accept(1), 3012);
}
