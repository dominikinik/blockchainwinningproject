package com.example.monitor.infrastructure.solana;

import java.util.List;

/** The Solana JSON-RPC calls the downtime publisher needs. All reads use {@code confirmed} commitment. */
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
	 * Where a sent transaction stands.
	 *
	 * @param confirmed whether it reached {@code confirmed} or {@code finalized}
	 * @param error     the transaction error as text, or {@code null} if it succeeded
	 */
	record SignatureStatus(boolean confirmed, String error) {
	}

	/**
	 * Reads an account.
	 *
	 * @param address the Base58 account address
	 * @return the account, or {@code null} when it doesn't exist
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	AccountInfo getAccountInfo(String address);

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
	 * Looks up a sent transaction.
	 *
	 * @param signature the Base58 transaction signature
	 * @return its status, or {@code null} when the node hasn't seen it (yet)
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	SignatureStatus getSignatureStatus(String signature);

	/**
	 * Reads the program logs of a confirmed transaction.
	 *
	 * @param signature the Base58 transaction signature
	 * @return the log lines, or {@code null} when the transaction isn't available yet
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	List<String> getTransactionLogs(String signature);

	/**
	 * Lists the recent transactions that touched an address.
	 *
	 * @param address the Base58 account address
	 * @param limit   the most signatures to return
	 * @return Base58 signatures, newest first; empty if there are none
	 * @throws SolanaRpcException if the node can't be reached or returns an error
	 */
	List<String> getSignaturesForAddress(String address, int limit);

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
