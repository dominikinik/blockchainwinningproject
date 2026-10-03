use anchor_lang::prelude::*;

#[constant]
pub const CONFIG_SEED: &[u8] = b"config";

#[constant]
pub const MONITOR_SEED: &[u8] = b"monitor";

#[constant]
pub const SLA_SEED: &[u8] = b"sla";

#[constant]
pub const WINDOW_SEED: &[u8] = b"window";

/// Defaults the admin passes to `initialize_config` outside of tests.
#[constant]
pub const DEFAULT_WINDOW_SECS: u32 = 3_600;

#[constant]
pub const DEFAULT_REPORT_GRACE_SECS: u32 = 600;

/// Hard cap on monitors per SLA; `Config::max_monitors_per_sla` may be lower, never higher.
#[constant]
pub const MAX_MONITORS_PER_SLA: u8 = 5;

#[constant]
pub const MAX_MONITOR_NAME_LEN: u32 = 32;

#[constant]
pub const MAX_SLA_NAME_LEN: u32 = 64;

#[constant]
pub const MAX_ENDPOINT_LEN: u32 = 200;

#[constant]
pub const MAX_SLA_DURATION_SECS: u32 = 90 * 24 * 3_600;

/// 90 days of 1-hour windows. Also caps SLAs under a shorter `window_secs`.
#[constant]
pub const MAX_WINDOWS: u32 = 2_160;

/// One bit per check slot in a window report bitmap.
#[constant]
pub const MAX_CHECKS_PER_WINDOW: u32 = 256;

#[constant]
pub const MIN_CHECK_INTERVAL_SECS: u32 = 10;

#[constant]
pub const MIN_TIMEOUT_MS: u32 = 100;

#[constant]
pub const MAX_TIMEOUT_MS: u32 = 30_000;

/// 10_000 basis points = 100.00% uptime.
#[constant]
pub const MAX_UPTIME_BPS: u16 = 10_000;
