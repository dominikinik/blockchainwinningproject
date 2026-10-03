//! LiteSVM harness shared by the instruction tests: runs the built program in-process.
#![allow(dead_code, clippy::result_large_err)]

use anchor_lang::{
    prelude::{Clock, Pubkey}, solana_program::instruction::Instruction, solana_program::system_program,
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

use uptime_deal::{error::DealError, state::Deal, DEAL_SEED, OBSERVATION_GRACE_SECONDS};

pub const SOL: u64 = 1_000_000_000;
/// Customer payment used by most tests.
pub const AMOUNT: u64 = 2 * SOL;
/// Uptime window used by most tests, in seconds.
pub const DURATION: u64 = 60;
/// Round length used by most tests: `DURATION / INTERVAL` = 10 rounds.
pub const INTERVAL: u64 = 6;
/// Rounds in a deal with the default terms.
pub const ROUNDS: u32 = (DURATION / INTERVAL) as u32;
/// Threshold used by most tests: 90%, so 9 of 10 rounds pass and 8 fail.
pub const MIN_BPS: u16 = 9_000;
/// Chain time at which every `Env` starts.
pub const T0: i64 = 1_700_000_000;

pub type TxResult = Result<TransactionMetadata, FailedTransactionMetadata>;

/// The terms of a deal, as passed to `create_deal`.
#[derive(Clone, Copy, Debug)]
pub struct Terms {
    pub amount: u64,
    pub stake: u64,
    pub duration: u64,
    pub interval: u64,
    pub min_bps: u16,
}

impl Default for Terms {
    /// The terms most tests use: `AMOUNT`, no guarantee, `DURATION`, `INTERVAL` and `MIN_BPS`.
    fn default() -> Self {
        Terms { amount: AMOUNT, stake: 0, duration: DURATION, interval: INTERVAL, min_bps: MIN_BPS }
    }
}

/// Derives the address of a deal.
///
/// # Arguments
///
/// * `payer` - the wallet that funded the deal.
/// * `deal_id` - the payer-chosen deal id.
///
/// # Returns
///
/// The `Deal` PDA for `["deal", payer, deal_id (LE)]`.
pub fn deal_pda(payer: &Pubkey, deal_id: u64) -> Pubkey {
    Pubkey::find_program_address(&[DEAL_SEED, payer.as_ref(), &deal_id.to_le_bytes()], &uptime_deal::ID).0
}

/// Builds an observation pattern: `up` UP rounds, then `down` DOWN rounds, then `missed` unobserved.
///
/// # Arguments
///
/// * `up` - rounds observed UP.
/// * `down` - rounds observed DOWN.
/// * `missed` - rounds never observed.
///
/// # Returns
///
/// The pattern for `Env::observe_all`.
pub fn pattern(up: usize, down: usize, missed: usize) -> Vec<Option<bool>> {
    let mut rounds = vec![Some(true); up];
    rounds.extend(vec![Some(false); down]);
    rounds.extend(vec![None; missed]);
    rounds
}

/// Extracts the custom program error code of a failed transaction.
///
/// # Arguments
///
/// * `res` - the transaction result.
///
/// # Returns
///
/// `Some(code)` for a `Custom(code)` instruction error, `None` on success or other errors.
pub fn custom_code(res: &TxResult) -> Option<u32> {
    let err = format!("{:?}", res.as_ref().err()?.err);
    let start = err.find("Custom(")? + "Custom(".len();
    err[start..].split(')').next()?.parse().ok()
}

/// Asserts that a transaction failed with a given `DealError`.
///
/// # Arguments
///
/// * `res` - the transaction result.
/// * `want` - the expected error (code `6000 + index`).
#[track_caller]
pub fn assert_err(res: TxResult, want: DealError) {
    assert_anchor_code(res, 6000 + want as u32);
}

/// Asserts that a transaction failed with a given raw program error code.
///
/// # Arguments
///
/// * `res` - the transaction result.
/// * `want` - the expected custom error code (e.g. an Anchor `ErrorCode as u32`).
#[track_caller]
pub fn assert_anchor_code(res: TxResult, want: u32) {
    assert_eq!(custom_code(&res), Some(want), "{:?}", res.map(|_| ()).map_err(|e| e.meta.logs));
}

/// Asserts that a transaction succeeded.
///
/// # Arguments
///
/// * `res` - the transaction result.
///
/// # Returns
///
/// The transaction metadata (logs, compute units) of the successful transaction.
#[track_caller]
pub fn assert_ok(res: TxResult) -> TransactionMetadata {
    match res {
        Ok(meta) => meta,
        Err(e) => panic!("{:?}\n{:#?}", e.err, e.meta.logs),
    }
}

/// An in-process chain with the program loaded and the three parties of a deal.
pub struct Env {
    pub svm: LiteSVM,
    pub payer: Keypair,
    pub recipient: Keypair,
    pub oracle: Keypair,
}

impl Env {
    /// Starts a chain with the program from `target/deploy/uptime_deal.so`.
    ///
    /// # Returns
    ///
    /// An `Env` at chain time `T0` whose payer and oracle hold 10 SOL each. The recipient has no
    /// account yet, so tests also cover paying a wallet that doesn't exist.
    pub fn new() -> Self {
        let mut svm = LiteSVM::new();
        svm.add_program(uptime_deal::ID, include_bytes!("../../../../target/deploy/uptime_deal.so"))
            .unwrap();
        let (payer, recipient, oracle) = (Keypair::new(), Keypair::new(), Keypair::new());
        svm.airdrop(&payer.pubkey(), 10 * SOL).unwrap();
        svm.airdrop(&oracle.pubkey(), 10 * SOL).unwrap();
        let mut env = Env { svm, payer, recipient, oracle };
        env.set_time(T0);
        env
    }

    /// Reads an account's lamports.
    ///
    /// # Arguments
    ///
    /// * `key` - the account address.
    ///
    /// # Returns
    ///
    /// The balance in lamports, or 0 when the account doesn't exist.
    pub fn balance(&self, key: &Pubkey) -> u64 {
        self.svm.get_balance(key).unwrap_or(0)
    }

    /// Tells whether an account exists with a non-zero balance.
    ///
    /// # Arguments
    ///
    /// * `key` - the account address.
    ///
    /// # Returns
    ///
    /// `true` if the account holds lamports, `false` if it is missing or closed.
    pub fn exists(&self, key: &Pubkey) -> bool {
        self.svm.get_account(key).is_some_and(|a| a.lamports > 0)
    }

    /// Deserializes a `Deal` account.
    ///
    /// # Arguments
    ///
    /// * `key` - the deal address.
    ///
    /// # Returns
    ///
    /// The decoded `Deal`; panics if the account is missing or malformed.
    pub fn deal(&self, key: &Pubkey) -> Deal {
        let acc = self.svm.get_account(key).expect("deal exists");
        Deal::try_deserialize(&mut &acc.data[..]).unwrap()
    }

    /// Address of one of this env's deals.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The `Deal` PDA.
    pub fn pda(&self, deal_id: u64) -> Pubkey {
        deal_pda(&self.payer.pubkey(), deal_id)
    }

    /// Signs and sends instructions in one transaction.
    ///
    /// # Arguments
    ///
    /// * `ixs` - the instructions to run.
    /// * `fee_payer` - pays the fee and signs.
    /// * `signers` - additional signers (the fee payer is skipped if repeated).
    ///
    /// # Returns
    ///
    /// The LiteSVM transaction result.
    pub fn send(&mut self, ixs: &[Instruction], fee_payer: &Keypair, signers: &[&Keypair]) -> TxResult {
        self.svm.expire_blockhash();
        let mut all: Vec<&Keypair> = vec![fee_payer];
        all.extend(signers.iter().filter(|s| s.pubkey() != fee_payer.pubkey()));
        let msg = Message::new(ixs, Some(&fee_payer.pubkey()));
        let tx = Transaction::new(&all, msg, self.svm.latest_blockhash());
        self.svm.send_transaction(tx)
    }

    /// Builds a `create_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `payer` - the funding wallet (must sign the transaction).
    /// * `recipient` - the provider, paid when the SLA is met.
    /// * `oracle` - the key allowed to record observations.
    /// * `deal_id` - the payer-chosen id.
    /// * `terms` - payment, guarantee, window, interval and threshold.
    ///
    /// # Returns
    ///
    /// The instruction, with the `deal` PDA derived from `payer` and `deal_id`.
    pub fn create_ix(payer: &Pubkey, recipient: &Pubkey, oracle: &Pubkey, deal_id: u64, terms: Terms) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::CreateDeal {
                payer: *payer,
                recipient: *recipient,
                oracle: *oracle,
                deal: deal_pda(payer, deal_id),
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: uptime_deal::instruction::CreateDeal {
                deal_id,
                amount_lamports: terms.amount,
                provider_stake_lamports: terms.stake,
                duration_seconds: terms.duration,
                check_interval_seconds: terms.interval,
                min_uptime_bps: terms.min_bps,
            }
            .data(),
        }
    }

    /// Builds an `accept_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `recipient` - the signer claiming to be the deal's recipient.
    /// * `deal` - the deal address.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn accept_ix(recipient: &Pubkey, deal: &Pubkey) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::AcceptDeal {
                recipient: *recipient,
                deal: *deal,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: uptime_deal::instruction::AcceptDeal {}.data(),
        }
    }

    /// Builds a `record_observation` instruction.
    ///
    /// # Arguments
    ///
    /// * `oracle` - the signer claiming to be the deal's oracle.
    /// * `deal` - the deal address.
    /// * `round` - the zero-based round.
    /// * `up` - the observation.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn observe_ix(oracle: &Pubkey, deal: &Pubkey, round: u32, up: bool) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::RecordObservation { oracle: *oracle, deal: *deal }.to_account_metas(None),
            data: uptime_deal::instruction::RecordObservation { round, up }.data(),
        }
    }

    /// Builds a `settle_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `caller` - any signer; pays the fee.
    /// * `deal` - the deal address.
    /// * `payer` - the account passed as the deal's payer.
    /// * `recipient` - the account passed as the deal's recipient.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn settle_ix(caller: &Pubkey, deal: &Pubkey, payer: &Pubkey, recipient: &Pubkey) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::SettleDeal {
                caller: *caller,
                deal: *deal,
                payer: *payer,
                recipient: *recipient,
            }
            .to_account_metas(None),
            data: uptime_deal::instruction::SettleDeal {}.data(),
        }
    }

    /// Builds a `cancel_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `payer` - the signer claiming to be the deal's payer.
    /// * `deal` - the deal address.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn cancel_ix(payer: &Pubkey, deal: &Pubkey) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::CancelDeal { payer: *payer, deal: *deal }.to_account_metas(None),
            data: uptime_deal::instruction::CancelDeal {}.data(),
        }
    }

    /// Reads the chain clock.
    ///
    /// # Returns
    ///
    /// The current `unix_timestamp` of the in-process chain.
    pub fn now(&self) -> i64 {
        self.svm.get_sysvar::<Clock>().unix_timestamp
    }

    /// Moves the chain clock.
    ///
    /// # Arguments
    ///
    /// * `unix_timestamp` - the new chain time in unix seconds.
    pub fn set_time(&mut self, unix_timestamp: i64) {
        let mut clock = self.svm.get_sysvar::<Clock>();
        clock.unix_timestamp = unix_timestamp;
        self.svm.set_sysvar::<Clock>(&clock);
    }

    /// Creates a deal with the default `Terms` between this env's payer, recipient and oracle.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the payer-chosen id.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the payer.
    pub fn create(&mut self, deal_id: u64) -> TxResult {
        self.create_with(deal_id, Terms::default())
    }

    /// Creates a deal between this env's payer, recipient and oracle.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the payer-chosen id.
    /// * `terms` - the deal terms.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the payer.
    pub fn create_with(&mut self, deal_id: u64, terms: Terms) -> TxResult {
        let ix = Self::create_ix(&self.payer.pubkey(), &self.recipient.pubkey(), &self.oracle.pubkey(), deal_id, terms);
        let payer = self.payer.insecure_clone();
        self.send(&[ix], &payer, &[])
    }

    /// Accepts one of this env's deals with an arbitrary signer.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the recipient; also pays the fee and the guarantee.
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn accept_as(&mut self, signer: &Keypair, deal_id: u64) -> TxResult {
        let ix = Self::accept_ix(&signer.pubkey(), &self.pda(deal_id));
        self.send(&[ix], signer, &[])
    }

    /// Accepts one of this env's deals as the recipient.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the recipient (which needs lamports first).
    pub fn accept(&mut self, deal_id: u64) -> TxResult {
        let recipient = self.recipient.insecure_clone();
        self.accept_as(&recipient, deal_id)
    }

    /// Records an observation with an arbitrary signer, at the current chain time.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the oracle; also pays the fee.
    /// * `deal_id` - the id the payer used.
    /// * `round` - the zero-based round.
    /// * `up` - the observation.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn observe_as(&mut self, signer: &Keypair, deal_id: u64, round: u32, up: bool) -> TxResult {
        let ix = Self::observe_ix(&signer.pubkey(), &self.pda(deal_id), round, up);
        self.send(&[ix], signer, &[])
    }

    /// Records an observation as the oracle, at the current chain time.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    /// * `round` - the zero-based round.
    /// * `up` - the observation.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn observe(&mut self, deal_id: u64, round: u32, up: bool) -> TxResult {
        let oracle = self.oracle.insecure_clone();
        self.observe_as(&oracle, deal_id, round, up)
    }

    /// Records one observation per round as the oracle, moving the clock to the window's end first.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used; the deal must be active.
    /// * `rounds` - `Some(up)` records round `i`, `None` leaves it unobserved.
    pub fn observe_all(&mut self, deal_id: u64, rounds: &[Option<bool>]) {
        let deal = self.deal(&self.pda(deal_id));
        self.set_time(deal.starts_at + deal.duration_seconds as i64);
        for (round, up) in rounds.iter().enumerate() {
            if let Some(up) = up {
                assert_ok(self.observe(deal_id, round as u32, *up));
            }
        }
    }

    /// Moves the clock to the first second at which a deal can be settled.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used; the deal must be active.
    pub fn to_settlement(&mut self, deal_id: u64) {
        let deal = self.deal(&self.pda(deal_id));
        self.set_time(deal.starts_at + deal.duration_seconds as i64 + OBSERVATION_GRACE_SECONDS);
    }

    /// Settles one of this env's deals with an arbitrary signer.
    ///
    /// # Arguments
    ///
    /// * `signer` - any key; pays the fee.
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn settle_as(&mut self, signer: &Keypair, deal_id: u64) -> TxResult {
        let ix = Self::settle_ix(&signer.pubkey(), &self.pda(deal_id), &self.payer.pubkey(), &self.recipient.pubkey());
        self.send(&[ix], signer, &[])
    }

    /// Settles one of this env's deals, signed and paid by the oracle (as the service does).
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn settle(&mut self, deal_id: u64) -> TxResult {
        let oracle = self.oracle.insecure_clone();
        self.settle_as(&oracle, deal_id)
    }

    /// Cancels one of this env's deals, signed and paid by the payer.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn cancel(&mut self, deal_id: u64) -> TxResult {
        let payer = self.payer.insecure_clone();
        self.cancel_as(&payer, deal_id)
    }

    /// Cancels one of this env's deals with an arbitrary signer.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the payer; also pays the fee.
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn cancel_as(&mut self, signer: &Keypair, deal_id: u64) -> TxResult {
        let ix = Self::cancel_ix(&signer.pubkey(), &self.pda(deal_id));
        self.send(&[ix], signer, &[])
    }
}
