use anchor_lang::prelude::*;

/// Seed prefix of the `Deal` PDA: `["deal", payer, deal_id (u64 LE)]`.
#[constant]
pub const DEAL_SEED: &[u8] = b"deal";

/// Uptime must be strictly above this many percent for the recipient to be paid.
#[constant]
pub const UPTIME_THRESHOLD_PERCENT: u64 = 99;

/// Smallest payment and smallest guarantee accepted. It is above the rent-exempt minimum of an
/// empty wallet (890,880 lamports), so no payout can leave a wallet below that minimum.
#[constant]
pub const MIN_DEAL_LAMPORTS: u64 = 1_000_000;

/// How long after `create_deal` the recipient may still accept the proposal, in seconds (one day).
#[constant]
pub const ACCEPT_TIMEOUT_SECONDS: i64 = 86_400;

/// Longest uptime window a deal may run, in seconds (one day).
#[constant]
pub const MAX_DEAL_DURATION_SECONDS: u64 = 86_400;

/// How long after its window ends a deal can still only be settled by the oracle. After that the
/// payer may `cancel_deal` and take the escrow back, so a missing oracle never locks it forever.
#[constant]
pub const CANCEL_TIMEOUT_SECONDS: i64 = 600;
