package com.example.monitor.infrastructure.solana;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.monitor.infrastructure.config.HttpTimeouts;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

class HttpSolanaRpcTest {

	private static final String URL = "http://rpc.test";

	private final RestClient.Builder builder = RestClient.builder().baseUrl(URL);

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final HttpSolanaRpc rpc = new HttpSolanaRpc(builder);

	@Test
	void sendsTransactionsAndReadsBalancesAndAirdrops() {
		expect("getLatestBlockhash", "{\"value\":{\"blockhash\":\"11111111111111111111111111111111\"}}");
		server.expect(requestTo(URL))
			.andExpect(jsonPath("$.method").value("sendTransaction"))
			.andExpect(jsonPath("$.params[0]").value(Base64.getEncoder().encodeToString(new byte[] { 9 })))
			.andExpect(jsonPath("$.params[1].encoding").value("base64"))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"Sig\"}", MediaType.APPLICATION_JSON));
		expect("getBalance", "{\"value\":42}");
		expect("requestAirdrop", "\"AirSig\"");

		assertThat(rpc.getLatestBlockhash()).isEqualTo(new byte[32]);
		assertThat(rpc.sendTransaction(new byte[] { 9 })).isEqualTo("Sig");
		assertThat(rpc.getBalance("Addr")).isEqualTo(42);
		assertThat(rpc.requestAirdrop("Addr", 5)).isEqualTo("AirSig");
		server.verify();
	}

	@Test
	void listsProgramAccountsMatchingAMemcmpFilter() {
		server.expect(requestTo(URL))
			.andExpect(jsonPath("$.method").value("getProgramAccounts"))
			.andExpect(jsonPath("$.params[0]").value("Prog"))
			.andExpect(jsonPath("$.params[1].encoding").value("base64"))
			.andExpect(jsonPath("$.params[1].commitment").value("confirmed"))
			.andExpect(jsonPath("$.params[1].filters[0].memcmp.offset").value(72))
			.andExpect(jsonPath("$.params[1].filters[0].memcmp.bytes").value("Oracle"))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[{\"pubkey\":\"Deal1\",\"account\":"
					+ "{\"owner\":\"Prog\",\"lamports\":9,\"data\":[\"" + Base64.getEncoder().encodeToString(new byte[] { 3 })
					+ "\",\"base64\"]}}]}", MediaType.APPLICATION_JSON));
		expect("getProgramAccounts", "[]");
		expect("getProgramAccounts", "null");

		List<SolanaRpc.ProgramAccount> accounts = rpc.getProgramAccounts("Prog", 72, "Oracle");
		assertThat(accounts).hasSize(1);
		assertThat(accounts.get(0).address()).isEqualTo("Deal1");
		assertThat(accounts.get(0).account().owner()).isEqualTo("Prog");
		assertThat(accounts.get(0).account().lamports()).isEqualTo(9);
		assertThat(accounts.get(0).account().data()).containsExactly(3);
		assertThat(rpc.getProgramAccounts("Prog", 72, "Oracle")).isEmpty();
		assertThat(rpc.getProgramAccounts("Prog", 72, "Oracle")).isEmpty();
		server.verify();
	}

	@Test
	void aNodeThatNeverAnswersTimesOutInsteadOfHanging() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
			RestClient.Builder slow = RestClient.builder()
				.baseUrl("http://127.0.0.1:" + silent.getLocalPort())
				.requestFactory(HttpTimeouts.timeouts(Duration.ofMillis(200)));
			HttpSolanaRpc stalled = new HttpSolanaRpc(slow);

			assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertThatThrownBy(() -> stalled.getBalance("Addr"))
				.isInstanceOf(SolanaRpcException.class)
				.hasMessageStartingWith("getBalance failed"));
		}
	}

	@Test
	void rpcErrorsAndHttpFailuresBecomeSolanaRpcExceptions() {
		server.expect(requestTo(URL))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32002,\"message\":\"Blockhash not found\"}}",
					MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL)).andRespond(withServerError());

		assertThatThrownBy(() -> rpc.sendTransaction(new byte[] { 1 })).isInstanceOf(SolanaRpcException.class)
			.hasMessage("sendTransaction failed: Blockhash not found");
		assertThatThrownBy(() -> rpc.getBalance("Addr")).isInstanceOf(SolanaRpcException.class);
	}

	private void expect(String rpcMethod, String result) {
		server.expect(requestTo(URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(jsonPath("$.jsonrpc").value("2.0"))
			.andExpect(jsonPath("$.method").value(rpcMethod))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}",
					MediaType.APPLICATION_JSON));
	}

}
