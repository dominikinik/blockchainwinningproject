package com.example.monitor.infrastructure.solana;

import java.util.List;

import com.example.monitor.domain.deal.DealChain;
import com.example.monitor.domain.deal.Verdict;
import com.example.monitor.infrastructure.solana.DealProgram.DealAccount;
import com.example.monitor.infrastructure.solana.DealProgram.Outcome;
import com.example.monitor.infrastructure.solana.SolanaRpc.AccountInfo;
import com.example.monitor.infrastructure.solana.SolanaRpc.SignatureStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link DealChain} over JSON-RPC: reads deal accounts and sends {@code settle_deal} signed by the oracle. */
public class SolanaDealChain implements DealChain {

	private static final Logger log = LoggerFactory.getLogger(SolanaDealChain.class);

	/** How many recent transactions of a vanished deal account are searched for its closing event. */
	static final int HISTORY_LIMIT = 10;

	private final SolanaRpc rpc;

	private final OracleKey oracle;

	private final String programId;

	private final String rpcUrl;

	private final long minLamports;

	private final long airdropLamports;

	public SolanaDealChain(SolanaRpc rpc, OracleKey oracle, String programId, String rpcUrl, long minLamports,
			long airdropLamports) {
		this.rpc = rpc;
		this.oracle = oracle;
		this.programId = programId;
		this.rpcUrl = rpcUrl;
		this.minLamports = minLamports;
		this.airdropLamports = airdropLamports;
	}

	@Override
	public String programId() {
		return programId;
	}

	@Override
	public String oracleAddress() {
		return oracle.address();
	}

	@Override
	public String rpcUrl() {
		return rpcUrl;
	}

	@Override
	public ChainDeal readDeal(String address) {
		Base58.decodePublicKey(address);
		AccountInfo account = rpc.getAccountInfo(address);
		if (account == null) {
			return null;
		}
		if (!programId.equals(account.owner())) {
			throw new IllegalArgumentException("Account " + address + " is not owned by the uptime_deal program");
		}
		DealAccount deal = DealProgram.decodeDeal(account.data());
		return new ChainDeal(deal.payer(), deal.recipient(), deal.oracle(), deal.amountLamports(), deal.startsAt(),
				deal.durationSeconds());
	}

	@Override
	public String sendSettle(String address, ChainDeal deal, Verdict verdict) {
		DealAccount account = new DealAccount(deal.payer(), deal.recipient(), deal.oracle(), 0, deal.amountLamports(),
				deal.startsAt(), deal.durationSeconds());
		byte[] tx = SolanaTransaction.signed(oracle, List.of(DealProgram.settleInstruction(programId, oracle.address(),
				address, account, verdict.upSeconds(), verdict.totalSeconds())), rpc.getLatestBlockhash());
		return rpc.sendTransaction(tx);
	}

	@Override
	public TxStatus status(String signature) {
		SignatureStatus status = rpc.getSignatureStatus(signature);
		return status == null ? null : new TxStatus(status.confirmed(), status.error());
	}

	@Override
	public Closure closureIn(String signature, String address) {
		List<String> logs = rpc.getTransactionLogs(signature);
		return logs == null ? null : toClosure(DealProgram.closedBy(logs, address));
	}

	@Override
	public FoundClosure findClosure(String address) {
		for (String signature : rpc.getSignaturesForAddress(address, HISTORY_LIMIT)) {
			Closure closure = closureIn(signature, address);
			if (closure != null) {
				return new FoundClosure(signature, closure);
			}
		}
		return null;
	}

	@Override
	public void ensureOracleFunded() {
		if (minLamports <= 0) {
			return;
		}
		try {
			if (rpc.getBalance(oracle.address()) < minLamports) {
				rpc.requestAirdrop(oracle.address(), airdropLamports);
				log.info("Requested an airdrop for oracle {}", oracle.address());
			}
		}
		catch (SolanaRpc.SolanaRpcException e) {
			log.warn("Could not fund oracle {}: {}", oracle.address(), e.getMessage());
		}
	}

	private static Closure toClosure(Outcome outcome) {
		return outcome == null ? null
				: new Closure(outcome.cancelled(), outcome.paidToRecipient(), outcome.upSeconds(), outcome.totalSeconds());
	}

}
