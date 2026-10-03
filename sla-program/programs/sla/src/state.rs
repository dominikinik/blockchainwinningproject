use anchor_lang::prelude::*;

/// Global settings. PDA `["config"]`. Written once by `initialize_config`.
#[account]
#[derive(InitSpace)]
pub struct Config {
    pub admin: Pubkey,
    pub window_secs: u32,
    pub report_grace_secs: u32,
    pub max_monitors_per_sla: u8,
    pub bump: u8,
}

/// A registered monitor node. PDA `["monitor", authority]`, where `authority` is the wallet the
/// node signs reports with.
#[account]
#[derive(InitSpace)]
pub struct Monitor {
    pub authority: Pubkey,
    #[max_len(32)]
    pub name: String,
    pub active: bool,
    pub reports_submitted: u64,
    /// Check slots this monitor reported as checked, across finalized windows.
    pub slots_voted: u64,
    /// Of those, the counted slots where the monitor's up/down matched consensus.
    pub slots_agreed: u64,
    pub bump: u8,
}

/// Consensus result of one finalized window.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, Default, PartialEq, Eq, InitSpace)]
pub struct WindowResult {
    /// Slots decided UP.
    pub up: u16,
    /// Slots decided at all (UP or DOWN). Slots with fewer than `consensus_required` votes are not counted.
    pub counted: u16,
}

#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, PartialEq, Eq, InitSpace)]
pub enum Recipient {
    Provider,
    Customer,
}

/// One service-level agreement. PDA `["sla", customer, sla_id]`.
/// The escrow is held as lamports in this account above its rent-exempt minimum.
///
/// Field order is part of the contract: `customer` (offset 8), `provider` (40), and
/// `monitors` (length at 88, entry `i` at 92 + 32*i) sit before any variable-length field so
/// clients can filter `getProgramAccounts` with memcmp. Don't move them.
#[account]
#[derive(InitSpace)]
pub struct Sla {
    pub customer: Pubkey,
    pub provider: Pubkey,
    pub sla_id: [u8; 16],
    /// Monitor authorities (wallets), fixed for the SLA's lifetime. Report bitmaps are indexed
    /// by position in this list.
    #[max_len(5)]
    pub monitors: Vec<Pubkey>,
    #[max_len(64)]
    pub name: String,
    #[max_len(200)]
    pub endpoint: String,
    pub escrow_lamports: u64,
    /// 9_990 = 99.90%.
    pub required_uptime_bps: u16,
    pub start_ts: i64,
    pub end_ts: i64,
    pub check_interval_secs: u32,
    pub timeout_ms: u32,
    pub consensus_required: u8,
    /// Copied from `Config` at creation so the schedule never changes under a live SLA.
    pub window_secs: u32,
    pub report_grace_secs: u32,
    pub total_windows: u32,
    pub next_window_to_finalize: u32,
    pub up_checks: u64,
    pub counted_checks: u64,
    /// One entry per finalized window, in order. The account is sized for `total_windows` entries.
    #[max_len(0)]
    pub window_results: Vec<WindowResult>,
    pub settled: bool,
    pub recipient: Option<Recipient>,
    pub bump: u8,
}

impl Sla {
    /// Account size (with discriminator) for an SLA with `total_windows` windows.
    pub fn space(total_windows: u32) -> usize {
        8 + Sla::INIT_SPACE + total_windows as usize * WindowResult::INIT_SPACE
    }

    /// Number of windows covering `duration_secs`; the last one may be shorter.
    pub fn window_count(duration_secs: u32, window_secs: u32) -> u32 {
        if window_secs == 0 {
            return 0;
        }
        duration_secs.div_ceil(window_secs)
    }
}

/// One monitor's observations for one window. Bit `j` of `checked`/`up` (byte `j / 8`,
/// bit `j % 8`, LSB first) is check slot `j`, at `window_start + j * check_interval_secs`.
#[derive(AnchorSerialize, AnchorDeserialize, Clone, Copy, Debug, Default, PartialEq, Eq, InitSpace)]
pub struct MonitorReport {
    pub checked: [u8; 32],
    pub up: [u8; 32],
    pub submitted: bool,
}

/// Reports for one window of one SLA. PDA `["window", sla, window_index (u32 LE)]`.
/// Created by the first report, closed by `finalize_window` (rent refunded to `payer`).
#[account]
#[derive(InitSpace)]
pub struct WindowReport {
    pub sla: Pubkey,
    pub window_index: u32,
    /// Indexed like `Sla::monitors`; entries past `monitors.len()` stay empty.
    /// Length is `MAX_MONITORS_PER_SLA` (a literal so the IDL gets a fixed array).
    pub per_monitor: [MonitorReport; 5],
    /// Whoever paid rent at creation (the first reporting monitor).
    pub payer: Pubkey,
    pub bump: u8,
}
