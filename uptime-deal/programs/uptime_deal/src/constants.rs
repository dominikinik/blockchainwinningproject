use anchor_lang::prelude::*;

/// Seed prefix of the `Deal` PDA: `["deal", payer, deal_id (u64 LE)]`.
#[constant]
pub const DEAL_SEED: &[u8] = b"deal";

/// Uptime must be strictly above this many percent for the recipient to be paid.
#[constant]
pub const UPTIME_THRESHOLD_PERCENT: u64 = 99;

/// Smallest escrow accepted. It is above the rent-exempt minimum of an empty wallet
/// (890,880 lamports), so paying a recipient that doesn't exist yet always succeeds.
#[constant]
pub const MIN_DEAL_LAMPORTS: u64 = 1_000_000;
