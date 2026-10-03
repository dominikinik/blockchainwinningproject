package com.example.monitor.infrastructure.solana;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.DowntimeReport;
import com.example.monitor.infrastructure.solana.SolanaTransaction.AccountMeta;
import com.example.monitor.infrastructure.solana.SolanaTransaction.Instruction;

/**
 * {@link DowntimePublisher} that records each report on chain as an SPL Memo transaction signed by the
 * oracle key, so anyone can read a service's downtime history from the oracle's transactions. The memo
 * is a compact JSON object, e.g.
 * {@code {"app":"uptime-monitor","serviceId":"…","trigger":"Downtime","totalDowntimeMs":4000,
 * "downtimeChecks":2,"internalErrors":0,"active":true,"at":"2026-10-04T12:00:00Z"}}.
 */
public class SolanaMemoDowntimePublisher implements DowntimePublisher {

	/** The SPL Memo v2 program, deployed on every cluster including the local test validator. */
	public static final String MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";

	private static final Logger log = LoggerFactory.getLogger(SolanaMemoDowntimePublisher.class);

	private final SolanaRpc rpc;

	private final OracleKey oracle;

	private final long minLamports;

	private final long airdropLamports;

	/**
	 * @param minLamports     balance below which the oracle asks the faucet for more; 0 disables this
	 * @param airdropLamports how much to ask the faucet for
	 */
	public SolanaMemoDowntimePublisher(SolanaRpc rpc, OracleKey oracle, long minLamports, long airdropLamports) {
		this.rpc = rpc;
		this.oracle = oracle;
		this.minLamports = minLamports;
		this.airdropLamports = airdropLamports;
	}

	@Override
	public void publish(DowntimeReport report) {
		topUp();
		byte[] tx = SolanaTransaction.signed(oracle, List.of(memoInstruction(report)), rpc.getLatestBlockhash());
		String signature = rpc.sendTransaction(tx);
		log.info("Published downtime of {} after {} ({} ms total): {}", report.serviceId(), report.trigger(),
				report.totalDowntime().toMillis(), signature);
	}

	/** The memo instruction, signed by the oracle so the memo is attributable to it. */
	public Instruction memoInstruction(DowntimeReport report) {
		return new Instruction(Base58.decodePublicKey(MEMO_PROGRAM_ID),
				List.of(new AccountMeta(oracle.publicKey(), true, true)),
				memo(report).getBytes(StandardCharsets.UTF_8));
	}

	/** The memo text; every field is a UUID, an event name, a number, a boolean or an ISO instant. */
	public static String memo(DowntimeReport report) {
		return String.format(Locale.ROOT,
				"{\"app\":\"uptime-monitor\",\"serviceId\":\"%s\",\"trigger\":\"%s\",\"totalDowntimeMs\":%d,"
						+ "\"downtimeChecks\":%d,\"internalErrors\":%d,\"active\":%b,\"at\":\"%s\"}",
				report.serviceId(), report.trigger(), report.totalDowntime().toMillis(), report.downtimeChecks(),
				report.internalErrors(), report.active(), report.reportedAt());
	}

	private void topUp() {
		if (minLamports <= 0) {
			return;
		}
		try {
			if (rpc.getBalance(oracle.address()) < minLamports) {
				rpc.requestAirdrop(oracle.address(), airdropLamports);
			}
		}
		catch (SolanaRpc.SolanaRpcException e) {
			log.debug("Oracle airdrop failed: {}", e.getMessage());
		}
	}

}
