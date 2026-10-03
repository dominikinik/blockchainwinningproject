package com.example.uptime.solana;

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

import com.example.uptime.deal.DealConfig;
import com.example.uptime.solana.SolanaRpc.SignatureStatus;
import com.example.uptime.solana.SolanaRpc.SolanaRpcException;

class HttpSolanaRpcTest {

	private static final String URL = "http://rpc.test";

	private final RestClient.Builder builder = RestClient.builder().baseUrl(URL);

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private final HttpSolanaRpc rpc = new HttpSolanaRpc(builder);

	@Test
	void readsAccountsAndMissingAccounts() {
		expect("getAccountInfo", "{\"value\":{\"owner\":\"Own\",\"lamports\":7,\"data\":[\""
				+ Base64.getEncoder().encodeToString(new byte[] { 1, 2 }) + "\",\"base64\"]}}");
		expect("getAccountInfo", "{\"value\":null}");

		SolanaRpc.AccountInfo info = rpc.getAccountInfo("Addr");
		assertThat(info.owner()).isEqualTo("Own");
		assertThat(info.lamports()).isEqualTo(7);
		assertThat(info.data()).containsExactly(1, 2);
		assertThat(rpc.getAccountInfo("Addr")).isNull();
		server.verify();
	}

	@Test
	void sendsTransactionsAndReadsStatusesLogsBalancesAndAirdrops() {
		expect("getLatestBlockhash", "{\"value\":{\"blockhash\":\"11111111111111111111111111111111\"}}");
		server.expect(requestTo(URL))
			.andExpect(jsonPath("$.method").value("sendTransaction"))
			.andExpect(jsonPath("$.params[0]").value(Base64.getEncoder().encodeToString(new byte[] { 9 })))
			.andExpect(jsonPath("$.params[1].encoding").value("base64"))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"Sig\"}", MediaType.APPLICATION_JSON));
		expect("getSignatureStatuses", "{\"value\":[{\"confirmationStatus\":\"confirmed\",\"err\":null}]}");
		expect("getSignatureStatuses", "{\"value\":[{\"confirmationStatus\":\"processed\",\"err\":{\"x\":1}}]}");
		expect("getSignatureStatuses", "{\"value\":[null]}");
		expect("getTransaction", "{\"meta\":{\"logMessages\":[\"a\",\"b\"]}}");
		expect("getTransaction", "null");
		expect("getBalance", "{\"value\":42}");
		expect("requestAirdrop", "\"AirSig\"");

		assertThat(rpc.getLatestBlockhash()).isEqualTo(new byte[32]);
		assertThat(rpc.sendTransaction(new byte[] { 9 })).isEqualTo("Sig");
		assertThat(rpc.getSignatureStatus("Sig")).isEqualTo(new SignatureStatus(true, null));
		assertThat(rpc.getSignatureStatus("Sig")).isEqualTo(new SignatureStatus(false, "{x=1}"));
		assertThat(rpc.getSignatureStatus("Sig")).isNull();
		assertThat(rpc.getTransactionLogs("Sig")).isEqualTo(List.of("a", "b"));
		assertThat(rpc.getTransactionLogs("Sig")).isNull();
		assertThat(rpc.getBalance("Addr")).isEqualTo(42);
		assertThat(rpc.requestAirdrop("Addr", 5)).isEqualTo("AirSig");
		server.verify();
	}

	@Test
	void listsRecentSignaturesNewestFirst() {
		server.expect(requestTo(URL))
			.andExpect(jsonPath("$.method").value("getSignaturesForAddress"))
			.andExpect(jsonPath("$.params[0]").value("Addr"))
			.andExpect(jsonPath("$.params[1].limit").value(10))
			.andExpect(jsonPath("$.params[1].commitment").value("confirmed"))
			.andRespond(withSuccess("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":[{\"signature\":\"S2\",\"err\":null},"
					+ "{\"signature\":\"S1\"}]}", MediaType.APPLICATION_JSON));
		expect("getSignaturesForAddress", "[]");
		expect("getSignaturesForAddress", "null");

		assertThat(rpc.getSignaturesForAddress("Addr", 10)).containsExactly("S2", "S1");
		assertThat(rpc.getSignaturesForAddress("Addr", 10)).isEmpty();
		assertThat(rpc.getSignaturesForAddress("Addr", 10)).isEmpty();
		server.verify();
	}

	@Test
	void aNodeThatNeverAnswersTimesOutInsteadOfHanging() throws Exception {
		try (ServerSocket silent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
			RestClient.Builder slow = RestClient.builder()
				.baseUrl("http://127.0.0.1:" + silent.getLocalPort())
				.requestFactory(DealConfig.timeouts(Duration.ofMillis(200)));
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
