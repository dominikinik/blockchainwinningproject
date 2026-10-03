package com.example.uptime.deal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.example.uptime.solana.HttpSolanaRpc;
import com.example.uptime.solana.OracleKey;
import com.example.uptime.solana.SolanaRpc;

@Configuration
public class DealConfig {

	@Bean
	OracleKey oracleKey(DealProperties properties) throws IOException {
		String path = properties.oracleKeypair();
		return path == null || path.isBlank() ? OracleKey.generate() : OracleKey.loadOrCreate(Path.of(path));
	}

	@Bean
	SolanaRpc solanaRpc(DealProperties properties) {
		return new HttpSolanaRpc(RestClient.builder()
			.baseUrl(properties.rpcUrl())
			.requestFactory(timeouts(Duration.ofMillis(properties.rpcTimeoutMs()))));
	}

	/**
	 * Builds the request factory of the RPC client.
	 *
	 * @param timeout connect and read timeout
	 * @return a factory whose calls fail instead of hanging on a stalled node
	 */
	public static SimpleClientHttpRequestFactory timeouts(Duration timeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}

}
