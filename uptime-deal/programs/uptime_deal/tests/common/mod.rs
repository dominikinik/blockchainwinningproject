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

use uptime_deal::{error::DealError, state::Deal, DEAL_SEED};

pub const SOL: u64 = 1_000_000_000;
/// Payer's payment used by most tests.
pub const AMOUNT: u64 = 2 * SOL;
/// Recipient's guarantee used by most tests; differs from `AMOUNT` so balances tell them apart.
pub const GUARANTEE: u64 = 3 * SOL;
/// Uptime window used by most tests, in seconds.
pub const DURATION: u64 = 60;

pub type TxResult = Result<TransactionMetadata, FailedTransactionMetadata>;

/// Derives the address of a deal.
///
/// # Arguments
///
/// * `payer` - the wallet that proposed the deal.
/// * `deal_id` - the payer-chosen deal id.
///
/// # Returns
///
/// The `Deal` PDA for `["deal", payer, deal_id (LE)]`.
pub fn deal_pda(payer: &Pubkey, deal_id: u64) -> Pubkey {
    Pubkey::find_program_address(&[DEAL_SEED, payer.as_ref(), &deal_id.to_le_bytes()], &uptime_deal::ID).0
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

/// The terms passed to `accept_deal`.
#[derive(Clone, Copy, Debug)]
pub struct Terms {
    /// The payer's payment.
    pub amount: u64,
    /// The recipient's guarantee.
    pub guarantee: u64,
    /// Window length in seconds.
    pub duration: u64,
    /// The oracle.
    pub oracle: Pubkey,
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
    /// An `Env` whose payer, recipient and oracle hold 10 SOL each. The recipient needs funds because
    /// it signs `accept_deal` and locks the guarantee.
    pub fn new() -> Self {
        let mut svm = LiteSVM::new();
        svm.add_program(uptime_deal::ID, include_bytes!("../../../../target/deploy/uptime_deal.so"))
            .unwrap();
        let (payer, recipient, oracle) = (Keypair::new(), Keypair::new(), Keypair::new());
        svm.airdrop(&payer.pubkey(), 10 * SOL).unwrap();
        svm.airdrop(&recipient.pubkey(), 10 * SOL).unwrap();
        svm.airdrop(&oracle.pubkey(), 10 * SOL).unwrap();
        Env { svm, payer, recipient, oracle }
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
    /// * `payer` - the proposing wallet (must sign the transaction).
    /// * `recipient` - the wallet that must accept and is paid on high uptime.
    /// * `oracle` - the key allowed to settle.
    /// * `deal_id` - the payer-chosen id.
    /// * `amount` - lamports the payer locks now.
    /// * `guarantee` - lamports the recipient must lock on acceptance.
    /// * `duration` - uptime window in seconds.
    ///
    /// # Returns
    ///
    /// The instruction, with the `deal` PDA derived from `payer` and `deal_id`.
    pub fn create_ix(
        payer: &Pubkey,
        recipient: &Pubkey,
        oracle: &Pubkey,
        deal_id: u64,
        amount: u64,
        guarantee: u64,
        duration: u64,
    ) -> Instruction {
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
                amount_lamports: amount,
                guarantee_lamports: guarantee,
                duration_seconds: duration,
            }
            .data(),
        }
    }

    /// Builds an `accept_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `recipient` - the signer accepting the deal and paying the guarantee.
    /// * `deal` - the deal address.
    /// * `terms` - the terms the recipient agrees to.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn accept_ix(recipient: &Pubkey, deal: &Pubkey, terms: Terms) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::AcceptDeal {
                recipient: *recipient,
                deal: *deal,
                system_program: system_program::ID,
            }
            .to_account_metas(None),
            data: uptime_deal::instruction::AcceptDeal {
                amount_lamports: terms.amount,
                guarantee_lamports: terms.guarantee,
                duration_seconds: terms.duration,
                oracle: terms.oracle,
            }
            .data(),
        }
    }

    /// Builds a `settle_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `oracle` - the signer reporting the uptime.
    /// * `deal` - the deal address.
    /// * `payer` - the account passed as the deal's payer.
    /// * `recipient` - the account passed as the deal's recipient.
    /// * `up` - seconds up.
    /// * `total` - seconds measured.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn settle_ix(oracle: &Pubkey, deal: &Pubkey, payer: &Pubkey, recipient: &Pubkey, up: u64, total: u64) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::SettleDeal {
                oracle: *oracle,
                deal: *deal,
                payer: *payer,
                recipient: *recipient,
            }
            .to_account_metas(None),
            data: uptime_deal::instruction::SettleDeal { up_seconds: up, total_seconds: total }.data(),
        }
    }

    /// Builds a `cancel_deal` instruction.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the deal's payer or recipient.
    /// * `deal` - the deal address.
    /// * `payer` - the account passed as the deal's payer.
    /// * `recipient` - the account passed as the deal's recipient.
    ///
    /// # Returns
    ///
    /// The instruction.
    pub fn cancel_ix(signer: &Pubkey, deal: &Pubkey, payer: &Pubkey, recipient: &Pubkey) -> Instruction {
        Instruction {
            program_id: uptime_deal::ID,
            accounts: uptime_deal::accounts::CancelDeal {
                signer: *signer,
                deal: *deal,
                payer: *payer,
                recipient: *recipient,
            }
            .to_account_metas(None),
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

    /// The terms of a deal between this env's parties with `GUARANTEE` and a `DURATION` window.
    ///
    /// # Arguments
    ///
    /// * `amount` - the payer's payment.
    ///
    /// # Returns
    ///
    /// The terms a matching `accept_deal` must carry.
    pub fn terms(&self, amount: u64) -> Terms {
        Terms { amount, guarantee: GUARANTEE, duration: DURATION, oracle: self.oracle.pubkey() }
    }

    /// Proposes a deal between this env's payer, recipient and oracle with `GUARANTEE` and a
    /// `DURATION` window.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the payer-chosen id.
    /// * `amount` - the payer's payment.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the payer.
    pub fn propose(&mut self, deal_id: u64, amount: u64) -> TxResult {
        self.create_for(deal_id, amount, GUARANTEE, DURATION)
    }

    /// Proposes a deal between this env's payer, recipient and oracle.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the payer-chosen id.
    /// * `amount` - the payer's payment.
    /// * `guarantee` - the guarantee the recipient must lock.
    /// * `duration` - uptime window in seconds.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the payer.
    pub fn create_for(&mut self, deal_id: u64, amount: u64, guarantee: u64, duration: u64) -> TxResult {
        let ix = Self::create_ix(
            &self.payer.pubkey(),
            &self.recipient.pubkey(),
            &self.oracle.pubkey(),
            deal_id,
            amount,
            guarantee,
            duration,
        );
        let payer = self.payer.insecure_clone();
        self.send(&[ix], &payer, &[])
    }

    /// Accepts one of this env's deals as the recipient, with the terms stored in the deal.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result, signed and paid by the recipient.
    pub fn accept(&mut self, deal_id: u64) -> TxResult {
        let deal = self.deal(&deal_pda(&self.payer.pubkey(), deal_id));
        let terms = Terms {
            amount: deal.amount_lamports,
            guarantee: deal.guarantee_lamports,
            duration: deal.duration_seconds,
            oracle: deal.oracle,
        };
        let recipient = self.recipient.insecure_clone();
        self.accept_as(&recipient, deal_id, terms)
    }

    /// Accepts one of this env's deals with an arbitrary signer and terms.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the recipient; also pays the fee.
    /// * `deal_id` - the id the payer used.
    /// * `terms` - the terms the signer agrees to.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn accept_as(&mut self, signer: &Keypair, deal_id: u64, terms: Terms) -> TxResult {
        let deal = deal_pda(&self.payer.pubkey(), deal_id);
        let ix = Self::accept_ix(&signer.pubkey(), &deal, terms);
        self.send(&[ix], signer, &[])
    }

    /// Proposes and accepts a deal with `GUARANTEE` and a `DURATION` window; panics if either fails.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the payer-chosen id.
    /// * `amount` - the payer's payment.
    pub fn open(&mut self, deal_id: u64, amount: u64) {
        assert_ok(self.propose(deal_id, amount));
        assert_ok(self.accept(deal_id));
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
    /// * `signer` - the key claiming to be the payer or the recipient; also pays the fee.
    /// * `deal_id` - the id the payer used.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn cancel_as(&mut self, signer: &Keypair, deal_id: u64) -> TxResult {
        let deal = deal_pda(&self.payer.pubkey(), deal_id);
        let ix = Self::cancel_ix(&signer.pubkey(), &deal, &self.payer.pubkey(), &self.recipient.pubkey());
        self.send(&[ix], signer, &[])
    }

    /// Settles one of this env's deals, signed and paid by the oracle.
    ///
    /// # Arguments
    ///
    /// * `deal_id` - the id the payer used.
    /// * `up` - seconds up.
    /// * `total` - seconds measured.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn settle(&mut self, deal_id: u64, up: u64, total: u64) -> TxResult {
        let oracle = self.oracle.insecure_clone();
        self.settle_as(&oracle, deal_id, up, total)
    }

    /// Settles one of this env's deals with an arbitrary signer.
    ///
    /// # Arguments
    ///
    /// * `signer` - the key claiming to be the oracle; also pays the fee.
    /// * `deal_id` - the id the payer used.
    /// * `up` - seconds up.
    /// * `total` - seconds measured.
    ///
    /// # Returns
    ///
    /// The transaction result.
    pub fn settle_as(&mut self, signer: &Keypair, deal_id: u64, up: u64, total: u64) -> TxResult {
        let deal = deal_pda(&self.payer.pubkey(), deal_id);
        let ix = Self::settle_ix(&signer.pubkey(), &deal, &self.payer.pubkey(), &self.recipient.pubkey(), up, total);
        self.send(&[ix], signer, &[])
    }
}
