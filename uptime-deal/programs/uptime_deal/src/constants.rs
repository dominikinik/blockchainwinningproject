use anchor_lang::prelude::*;

/// Seed prefix of the `Deal` PDA: `["deal", payer, deal_id (u64 LE)]`.
#[constant]
pub const DEAL_SEED: &[u8] = b"deal";

/// Uptime thresholds are in basis points: 10,000 bps = 100%.
#[constant]
pub const BPS_DENOMINATOR: u64 = 10_000;

/// Smallest customer payment accepted. It is above the rent-exempt minimum of an empty wallet
/// (890,880 lamports), so paying a recipient that doesn't exist yet always succeeds.
#[constant]
pub const MIN_DEAL_LAMPORTS: u64 = 1_000_000;

/// Longest uptime window a deal may run, in seconds (one day).
#[constant]
pub const MAX_DEAL_DURATION_SECONDS: u64 = 86_400;

/// Most monitoring rounds one deal may have. The deal stores one bit per round (1 KiB at most), so
/// `duration_seconds / check_interval_seconds` must stay at or below this.
#[constant]
pub const MAX_ROUNDS: u64 = 8_192;

/// How long after its window ends a deal still accepts observations (for the last rounds to land).
/// Settlement opens exactly when observations close, so the outcome can't depend on who acts first.
#[constant]
pub const OBSERVATION_GRACE_SECONDS: i64 = 10;
