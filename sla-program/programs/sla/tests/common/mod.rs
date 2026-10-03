//! LiteSVM harness shared by the instruction tests. Time is the `Clock` sysvar, set directly.
#![allow(dead_code)]

use anchor_lang::{
    prelude::{AccountMeta, Pubkey},
    solana_program::{instruction::Instruction, system_program},
    AccountDeserialize, InstructionData, ToAccountMetas,
};
use litesvm::{
    types::{FailedTransactionMetadata, TransactionMetadata},
    LiteSVM,
};
use solana_keypair::Keypair;
use solana_message::Message;
use solana_signer::Signer;
use solana_transaction::Transaction;

use sla::{
    error::SlaError, instructions::CreateSlaParams, state::*, ConfigParams, CONFIG_SEED,
    MONITOR_SEED, SLA_SEED, WINDOW_SEED,
};

pub const T0: i64 = 1_700_000_000;
/// Test schedule: 100s windows, 30s grace, 10s checks. A 250s SLA has windows of 10, 10, 5 slots.
pub const WINDOW: u32 = 100;
pub const GRACE: u32 = 30;
pub const INTERVAL: u32 = 10;
pub const DURATION: u32 = 250;
pub const ESCROW: u64 = 5_000_000_000;
pub const SOL: u64 = 1_000_000_000;

pub type TxResult = Result<TransactionMetadata, FailedTransactionMetadata>;

pub struct Env {
    pub svm: LiteSVM,
    pub admin: Keypair,
    pub now: i64,
}

pub fn config_pda() -> Pubkey {
    Pubkey::find_program_address(&[CONFIG_SEED], &sla::ID).0
}

pub fn monitor_pda(authority: &Pubkey) -> Pubkey {
    Pubkey::find_program_address(&[MONITOR_SEED, authority.as_ref()], &sla::ID).0
}

pub fn sla_pda(customer: &Pubkey, sla_id: &[u8; 16]) -> Pubkey {
    Pubkey::find_program_address(&[SLA_SEED, customer.as_ref(), sla_id], &sla::ID).0
}

pub fn window_pda(sla: &Pubkey, index: u32) -> Pubkey {
    Pubkey::find_program_address(&[WINDOW_SEED, sla.as_ref(), &index.to_le_bytes()], &sla::ID).0
}

/// Bitmap with bits `slots` set (LSB first).
pub fn bits(slots: impl IntoIterator<Item = u32>) -> [u8; 32] {
    let mut b = [0u8; 32];
    for j in slots {
        b[(j / 8) as usize] |= 1 << (j % 8);
    }
    b
}

/// The custom program error code of a failed transaction, if any.
pub fn custom_code(res: &TxResult) -> Option<u32> {
    let err = format!("{:?}", res.as_ref().err()?.err);
    let start = err.find("Custom(")? + "Custom(".len();
    err[start..].split(')').next()?.parse().ok()
}

#[track_caller]
pub fn assert_err(res: TxResult, want: SlaError) {
    let want = 6000 + want as u32;
    assert_eq!(custom_code(&res), Some(want), "{:?}", res.map(|_| ()).map_err(|e| e.meta.logs));
}

#[track_caller]
pub fn assert_anchor_err(res: TxResult, want: anchor_lang::error::ErrorCode) {
    assert_eq!(custom_code(&res), Some(want as u32), "{:?}", res.map(|_| ()).map_err(|e| e.meta.logs));
}

#[track_caller]
pub fn assert_ok(res: TxResult) -> TransactionMetadata {
    match res {
        Ok(meta) => meta,
        Err(e) => panic!("{:?}\n{:#?}", e.err, e.meta.logs),
    }
}

pub fn default_params(provider: Pubkey) -> CreateSlaParams {
    CreateSlaParams {
        provider,
        name: "api".into(),
        endpoint: "https://example.com/health".into(),
        escrow_lamports: ESCROW,
        required_uptime_bps: 9_000,
        duration_secs: DURATION,
        check_interval_secs: INTERVAL,
        timeout_ms: 2_000,
        consensus_required: 2,
    }
}

/// A created SLA and its parties.
pub struct TestSla {
    pub key: Pubkey,
    pub customer: Keypair,
    pub provider: Keypair,
    pub monitors: Vec<Keypair>,
}

impl Env {
    /// Loads the program and initializes the config with the test schedule.
    pub fn new() -> Self {
        let mut env = Self::bare();
        let admin = env.admin.insecure_clone();
        assert_ok(env.initialize_config(&admin, WINDOW, GRACE, 5));
        env
    }

    /// Loads the program without a config.
    pub fn bare() -> Self {
        let mut svm = LiteSVM::new();
        svm.add_program(sla::ID, include_bytes!("../../../../target/deploy/sla.so"))
            .unwrap();
        let admin = Keypair::new();
        svm.airdrop(&admin.pubkey(), 100 * SOL).unwrap();
        let mut env = Env { svm, admin, now: 0 };
        env.set_time(T0);
        env
    }

    pub fn set_time(&mut self, t: i64) {
        let mut clock: anchor_lang::prelude::Clock = self.svm.get_sysvar();
        clock.unix_timestamp = t;
        self.svm.set_sysvar(&clock);
        self.now = t;
    }

    pub fn funded(&mut self) -> Keypair {
        let k = Keypair::new();
        self.svm.airdrop(&k.pubkey(), 100 * SOL).unwrap();
        k
    }

    pub fn balance(&self, key: &Pubkey) -> u64 {
        self.svm.get_balance(key).unwrap_or(0)
    }

    pub fn exists(&self, key: &Pubkey) -> bool {
        self.svm.get_account(key).is_some_and(|a| a.lamports > 0)
    }

    pub fn read<T: AccountDeserialize>(&self, key: &Pubkey) -> T {
        let acc = self.svm.get_account(key).expect("account exists");
        T::try_deserialize(&mut &acc.data[..]).unwrap()
    }

    pub fn sla(&self, key: &Pubkey) -> Sla {
        self.read(key)
    }

    pub fn monitor(&self, authority: &Pubkey) -> Monitor {
        self.read(&monitor_pda(authority))
    }

    /// Sends `ixs` with `payer` paying fees; `payer` signs along with `signers`.
    pub fn send(&mut self, ixs: &[Instruction], payer: &Keypair, signers: &[&Keypair]) -> TxResult {
        self.svm.expire_blockhash();
        let mut all: Vec<&Keypair> = vec![payer];
        all.extend(signers.iter().filter(|s| s.pubkey() != payer.pubkey()));
        let msg = Message::new(ixs, Some(&payer.pubkey()));
        let tx = Transaction::new(&all, msg, self.svm.latest_blockhash());
        self.svm.send_transaction(tx)
    }

    // --- instructions ---

    pub fn initialize_config(&mut self, admin: &Keypair, window: u32, grace: u32, max: u8) -> TxResult {
        let ix = Instruction {
            program_id: sla::ID,
            accounts: sla::accounts::InitializeConfig {
                admin: admin.pubkey(),
                config: config_pda(),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: sla::instruction::InitializeConfig {
                params: ConfigParams { window_secs: window, report_grace_secs: grace, max_monitors_per_sla: max },
            }
            .data(),
        };
        self.send(&[ix], admin, &[])
    }

    pub fn register_monitor_as(&mut self, admin: &Keypair, authority: &Pubkey, name: &str) -> TxResult {
        let ix = Instruction {
            program_id: sla::ID,
            accounts: sla::accounts::RegisterMonitor {
                admin: admin.pubkey(),
                config: config_pda(),
                authority: *authority,
                monitor: monitor_pda(authority),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: sla::instruction::RegisterMonitor { name: name.into() }.data(),
        };
        self.send(&[ix], admin, &[])
    }

    /// Registers a new funded monitor wallet.
    pub fn new_monitor(&mut self) -> Keypair {
        let k = self.funded();
        let admin = self.admin.insecure_clone();
        assert_ok(self.register_monitor_as(&admin, &k.pubkey(), "node"));
        k
    }

    pub fn set_monitor_active_as(&mut self, admin: &Keypair, authority: &Pubkey, active: bool) -> TxResult {
        let ix = Instruction {
            program_id: sla::ID,
            accounts: sla::accounts::SetMonitorActive {
                admin: admin.pubkey(),
                config: config_pda(),
                monitor: monitor_pda(authority),
            }
            .to_account_metas(None),
            data: sla::instruction::SetMonitorActive { active }.data(),
        };
        self.send(&[ix], admin, &[])
    }

    /// `create_sla` with explicit remaining accounts.
    pub fn create_sla_raw(
        &mut self,
        customer: &Keypair,
        sla_id: [u8; 16],
        params: CreateSlaParams,
        monitors: Vec<Pubkey>,
        remaining: Vec<AccountMeta>,
    ) -> TxResult {
        let mut accounts = sla::accounts::CreateSla {
            customer: customer.pubkey(),
            config: config_pda(),
            sla: sla_pda(&customer.pubkey(), &sla_id),
            system_program: system_program::ID,
        }
        .to_account_metas(None);
        accounts.extend(remaining);
        let ix = Instruction {
            program_id: sla::ID,
            accounts,
            data: sla::instruction::CreateSla { sla_id, params, monitors }.data(),
        };
        self.send(&[ix], customer, &[])
    }

    pub fn create_sla_with(&mut self, customer: &Keypair, sla_id: [u8; 16], params: CreateSlaParams, monitors: &[Pubkey]) -> TxResult {
        let remaining = monitors.iter().map(|m| AccountMeta::new_readonly(monitor_pda(m), false)).collect();
        self.create_sla_raw(customer, sla_id, params, monitors.to_vec(), remaining)
    }

    /// Creates an SLA at the current time with `n` fresh monitors and consensus `k`.
    pub fn create_default_sla(&mut self, n: usize, k: u8) -> TestSla {
        let customer = self.funded();
        let provider = Keypair::new();
        let monitors: Vec<Keypair> = (0..n).map(|_| self.new_monitor()).collect();
        let keys: Vec<Pubkey> = monitors.iter().map(|m| m.pubkey()).collect();
        let params = CreateSlaParams { consensus_required: k, ..default_params(provider.pubkey()) };
        assert_ok(self.create_sla_with(&customer, [1; 16], params, &keys));
        TestSla { key: sla_pda(&customer.pubkey(), &[1; 16]), customer, provider, monitors }
    }

    pub fn submit_report_ix(&self, authority: &Pubkey, sla: &Pubkey, index: u32, checked: [u8; 32], up: [u8; 32]) -> Instruction {
        Instruction {
            program_id: sla::ID,
            accounts: sla::accounts::SubmitReport {
                monitor_authority: *authority,
                monitor: monitor_pda(authority),
                sla: *sla,
                window_report: window_pda(sla, index),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: sla::instruction::SubmitReport { window_index: index, checked, up }.data(),
        }
    }

    pub fn submit_report(&mut self, monitor: &Keypair, sla: &Pubkey, index: u32, checked: [u8; 32], up: [u8; 32]) -> TxResult {
        let ix = self.submit_report_ix(&monitor.pubkey(), sla, index, checked, up);
        self.send(&[ix], monitor, &[])
    }

    /// `finalize_window` with explicit accounts.
    pub fn finalize_raw(
        &mut self,
        cranker: &Keypair,
        sla: &Pubkey,
        index: u32,
        window_report: Pubkey,
        payer: Pubkey,
        remaining: Vec<AccountMeta>,
    ) -> TxResult {
        let mut accounts = sla::accounts::FinalizeWindow { sla: *sla, window_report, payer }.to_account_metas(None);
        accounts.extend(remaining);
        let ix = Instruction {
            program_id: sla::ID,
            accounts,
            data: sla::instruction::FinalizeWindow { window_index: index }.data(),
        };
        self.send(&[ix], cranker, &[])
    }

    /// Finalizes window `index` with the correct accounts; the rent goes to the report's payer.
    pub fn finalize(&mut self, cranker: &Keypair, sla: &Pubkey, index: u32) -> TxResult {
        let report = window_pda(sla, index);
        let payer = match self.svm.get_account(&report) {
            Some(a) if a.owner == sla::ID => self.read::<WindowReport>(&report).payer,
            _ => cranker.pubkey(),
        };
        let remaining = self.monitor_metas(sla);
        self.finalize_raw(cranker, sla, index, report, payer, remaining)
    }

    pub fn monitor_metas(&self, sla: &Pubkey) -> Vec<AccountMeta> {
        self.sla(sla).monitors.iter().map(|m| AccountMeta::new(monitor_pda(m), false)).collect()
    }

    pub fn settle_raw(&mut self, cranker: &Keypair, sla: &Pubkey, customer: Pubkey, provider: Pubkey) -> TxResult {
        let ix = Instruction {
            program_id: sla::ID,
            accounts: sla::accounts::Settle { sla: *sla, customer, provider }.to_account_metas(None),
            data: sla::instruction::Settle {}.data(),
        };
        self.send(&[ix], cranker, &[])
    }

    pub fn settle(&mut self, cranker: &Keypair, sla: &Pubkey) -> TxResult {
        let s = self.sla(sla);
        self.settle_raw(cranker, sla, s.customer, s.provider)
    }

    /// End of window `index` of `sla`.
    pub fn window_end(&self, sla: &Pubkey, index: u32) -> i64 {
        self.sla(sla).schedule().window_bounds(index).unwrap().1
    }
}
