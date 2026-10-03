package com.example.uptime.deal;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
		return new HttpSolanaRpc(RestClient.builder().baseUrl(properties.rpcUrl()));
	}

}
