package com.example.monitor.infrastructure.solana;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** {@link SolanaRpc} over HTTP JSON-RPC 2.0. */
public class HttpSolanaRpc implements SolanaRpc {

	private static final Map<String, Object> CONFIRMED = Map.of("commitment", "confirmed");

	private final RestClient client;

	private final AtomicLong ids = new AtomicLong();

	/**
	 * Creates a client for one RPC node.
	 *
	 * @param builder a {@link RestClient} builder; the base URL must already be the RPC node URL
	 */
	public HttpSolanaRpc(RestClient.Builder builder) {
		this.client = builder.build();
	}

	@Override
	public List<ProgramAccount> getProgramAccounts(String programId, int offset, String bytes) {
		List<?> entries = result("getProgramAccounts",
				List.of(programId,
						Map.of("encoding", "base64", "commitment", "confirmed", "filters",
								List.of(Map.of("memcmp", Map.of("offset", offset, "bytes", bytes))))),
				List.class);
		if (entries == null) {
			return List.of();
		}
		return entries.stream()
			.map(e -> (Map<?, ?>) e)
			.map(e -> new ProgramAccount((String) e.get("pubkey"), accountInfo((Map<?, ?>) e.get("account"))))
			.toList();
	}

	private static AccountInfo accountInfo(Map<?, ?> value) {
		List<?> data = (List<?>) value.get("data");
		return new AccountInfo((String) value.get("owner"), ((Number) value.get("lamports")).longValue(),
				Base64.getDecoder().decode((String) data.get(0)));
	}

	@Override
	public byte[] getLatestBlockhash() {
		Map<?, ?> value = (Map<?, ?>) result("getLatestBlockhash", List.of(CONFIRMED), Map.class).get("value");
		return Base58.decode((String) value.get("blockhash"));
	}

	@Override
	public String sendTransaction(byte[] transaction) {
		return result("sendTransaction", List.of(Base64.getEncoder().encodeToString(transaction),
				Map.of("encoding", "base64", "preflightCommitment", "confirmed")), String.class);
	}

	@Override
	public long getBalance(String address) {
		Map<?, ?> response = result("getBalance", List.of(address, CONFIRMED), Map.class);
		return ((Number) response.get("value")).longValue();
	}

	@Override
	public String requestAirdrop(String address, long lamports) {
		return result("requestAirdrop", List.of(address, lamports, CONFIRMED), String.class);
	}

	/** Calls one method and returns its {@code result}, which may be JSON {@code null}. */
	private <T> T result(String method, List<?> params, Class<T> type) {
		Map<?, ?> body;
		try {
			body = client.post()
				.contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("jsonrpc", "2.0", "id", ids.incrementAndGet(), "method", method, "params", params))
				.retrieve()
				.body(Map.class);
		}
		catch (RestClientException e) {
			throw new SolanaRpcException(method + " failed: " + e.getMessage(), e);
		}
		if (body == null) {
			throw new SolanaRpcException(method + " returned no body");
		}
		if (body.get("error") instanceof Map<?, ?> error) {
			throw new SolanaRpcException(method + " failed: " + error.get("message"));
		}
		return type.cast(body.get("result"));
	}

}
