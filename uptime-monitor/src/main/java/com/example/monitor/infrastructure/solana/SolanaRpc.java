package com.example.monitor.infrastructure.solana;

import java.util.List;

/** The Solana JSON-RPC calls the proxy needs. All reads use {@code confirmed} commitment. */
public interface SolanaRpc {

	/**
	 * An account as stored on chain.
	 *
	 * @param owner    the Base58 address of the owning program
	 * @param lamports the balance
	 * @param data     the raw account data
	 */
	record AccountInfo(String owner, long lamports, byte[] data) {
	}

	/**
	 * An account of a program, as listed by {@link #getProgramAccounts}.
	 *
	 * @param address the Base58 account address
	 * @param account the account
	 */
	record ProgramAccount(String address, AccountInfo account) {
	}

	/**
	 * Lists a program's accounts that hold given bytes at an offset (a {@code memcmp} filter).
	 *
	 * @param programId the Base58 owning program
	 * @param offset    byte offset into the account data
	 * @param bytes     the Base58 bytes that must appear there
	 * @return the matching accounts; empty if there are none
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	List<ProgramAccount> getProgramAccounts(String programId, int offset, String bytes);

	/**
	 * Fetches a recent blockhash for a new transaction.
	 *
	 * @return the 32-byte blockhash
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	byte[] getLatestBlockhash();

	/**
	 * Submits a signed transaction after a preflight simulation.
	 *
	 * @param transaction the serialized signed transaction
	 * @return the Base58 transaction signature
	 * @throws SolanaRpcException if the node rejects it, including a failed simulation
	 */
	String sendTransaction(byte[] transaction);

	/**
	 * Reads a balance.
	 *
	 * @param address the Base58 account address
	 * @return the lamports held, 0 for a missing account
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	long getBalance(String address);

	/**
	 * Asks the cluster faucet for lamports (localnet and devnet only).
	 *
	 * @param address  the Base58 address to fund
	 * @param lamports how much to request
	 * @return the Base58 signature of the airdrop transaction
	 * @throws SolanaRpcException if the cluster has no faucet or refuses
	 */
	String requestAirdrop(String address, long lamports);

	/** A failed RPC call: the node was unreachable, or it answered with a JSON-RPC error. */
	class SolanaRpcException extends RuntimeException {

		public SolanaRpcException(String message) {
			super(message);
		}

		public SolanaRpcException(String message, Throwable cause) {
			super(message, cause);
		}

	}

}
