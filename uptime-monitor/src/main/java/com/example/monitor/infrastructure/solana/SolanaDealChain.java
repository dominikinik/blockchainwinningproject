package com.example.monitor.infrastructure.solana;

import java.util.ArrayList;
import java.util.List;

import com.example.monitor.domain.DealChain;
import com.example.monitor.infrastructure.solana.DealProgram.DealAccount;
import com.example.monitor.infrastructure.solana.SolanaRpc.ProgramAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DealChain} over JSON-RPC: finds the program's deals that name this oracle with one {@code getProgramAccounts}
 * call (a {@code memcmp} on the oracle field), and sends {@code record_observation} signed by the oracle.
 */
public class SolanaDealChain implements DealChain {

	private static final Logger log = LoggerFactory.getLogger(SolanaDealChain.class);

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
	public List<ActiveDeal> activeDeals() {
		List<ActiveDeal> deals = new ArrayList<>();
		for (ProgramAccount account : rpc.getProgramAccounts(programId, DealProgram.ORACLE_OFFSET, oracle.address())) {
			DealAccount deal;
			try {
				deal = DealProgram.decodeDeal(account.account().data());
			}
			catch (IllegalArgumentException e) {
				continue;
			}
			if (deal.active() && deal.oracle().equals(oracle.address())) {
				deals.add(new ActiveDeal(account.address(), deal.startsAt(), deal.checkIntervalSeconds(),
						deal.totalRounds(), deal.recorded()));
			}
		}
		return deals;
	}

	@Override
	public String recordObservation(String address, int round, boolean up) {
		byte[] tx = SolanaTransaction.signed(oracle,
				List.of(DealProgram.observationInstruction(programId, oracle.address(), address, round, up)),
				rpc.getLatestBlockhash());
		return rpc.sendTransaction(tx);
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

}
