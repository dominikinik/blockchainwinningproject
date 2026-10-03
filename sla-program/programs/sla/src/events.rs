use anchor_lang::prelude::*;

use crate::state::Recipient;

#[event]
pub struct SlaCreated {
    pub sla: Pubkey,
    pub customer: Pubkey,
    pub provider: Pubkey,
    pub sla_id: [u8; 16],
    pub escrow_lamports: u64,
    pub required_uptime_bps: u16,
    pub start_ts: i64,
    pub end_ts: i64,
    pub total_windows: u32,
    pub monitors: Vec<Pubkey>,
}

/// Emitted per accepted report. The frontend reads recent ones to show per-monitor observations.
#[event]
pub struct ReportSubmitted {
    pub sla: Pubkey,
    pub window_index: u32,
    /// Monitor authority (wallet) that signed the report.
    pub monitor: Pubkey,
    pub checked: [u8; 32],
    pub up: [u8; 32],
    pub timestamp: i64,
}

#[event]
pub struct WindowFinalized {
    pub sla: Pubkey,
    pub window_index: u32,
    pub up: u16,
    pub counted: u16,
    /// False when no `WindowReport` existed (the window counted 0 checks).
    pub had_reports: bool,
}

#[event]
pub struct SlaSettled {
    pub sla: Pubkey,
    pub recipient: Recipient,
    pub amount_lamports: u64,
    pub up_checks: u64,
    pub counted_checks: u64,
}
