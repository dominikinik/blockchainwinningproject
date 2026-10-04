package com.example.monitor.interfaces.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.monitor.domain.DealChain;
import com.example.monitor.infrastructure.config.MonitorProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/deals")
@Tag(name = "Uptime deals", description = "How to create an uptime_deal that this proxy reports observations for")
public class DealController {

	private final ObjectProvider<DealChain> chain;

	private final MonitorProperties properties;

	public DealController(ObjectProvider<DealChain> chain, MonitorProperties properties) {
		this.chain = chain;
		this.properties = properties;
	}

	@GetMapping("/config")
	@Operation(summary = "Program and oracle to use when creating a deal",
			description = "A deal created with this oracle is found on chain by the proxy: once the provider accepts it, "
					+ "every health check is recorded as the UP/DOWN observation of the round that just ended. Rounds "
					+ "should match checkIntervalSeconds. Anyone settles the deal after its window. 503 if the "
					+ "blockchain is disabled.")
	public DealConfig config() {
		DealChain deals = chain.getIfAvailable();
		if (deals == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"The blockchain is off (monitor.blockchain.enabled=false)");
		}
		return new DealConfig(deals.programId(), deals.oracleAddress(), deals.rpcUrl(),
				Math.max(1, properties.checkIntervalMs() / 1_000));
	}

	/** Where a wallet must create a deal for this proxy to report its rounds. */
	public record DealConfig(String programId, String oracle, String rpcUrl, long checkIntervalSeconds) {
	}

}
