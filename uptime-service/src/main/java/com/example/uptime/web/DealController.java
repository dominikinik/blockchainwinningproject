package com.example.uptime.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.uptime.deal.DealProperties;
import com.example.uptime.deal.DealService;
import com.example.uptime.deal.TrackedDeal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/deals")
@Tag(name = "Uptime deals", description = "On-chain uptime deals that this service settles as their oracle")
public class DealController {

	private final DealService deals;

	private final DealProperties properties;

	public DealController(DealService deals, DealProperties properties) {
		this.deals = deals;
		this.properties = properties;
	}

	/**
	 * Tells a wallet how to create a deal this service can settle.
	 *
	 * @return the program id, the oracle key to name in {@code create_deal}, and the RPC URL
	 */
	@GetMapping("/config")
	@Operation(summary = "Program and oracle to use when creating a deal")
	public ConfigResponse config() {
		return new ConfigResponse(properties.programId(), deals.oracleAddress(), properties.rpcUrl());
	}

	/**
	 * Registers a deal created on chain, usually while it is still a proposal; its uptime window is the one
	 * stored in the deal account once the recipient accepts it.
	 *
	 * @param request the deal address
	 * @return the tracked deal (201), {@code PROPOSED} or {@code ACTIVE}
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Watch a deal and settle it when its window ends",
			description = "The deal must already exist on chain and name this service's oracle. A proposal is "
					+ "tracked as PROPOSED until its recipient accepts it on chain. The uptime window "
					+ "([starts_at, starts_at + duration_seconds), starting at acceptance) is read from the deal "
					+ "account, not from the request. "
					+ "400 for an invalid request or deal (including an on-chain duration above the maximum), "
					+ "409 if already registered, 502 if the RPC node fails.")
	public TrackedDeal register(@RequestBody RegisterRequest request) {
		return deals.register(request.address());
	}

	/**
	 * Reads one tracked deal.
	 *
	 * @param address Base58 deal address
	 * @return the tracked deal, or 404 if it isn't registered
	 */
	@GetMapping("/{address}")
	@Operation(summary = "One tracked deal")
	public TrackedDeal get(@PathVariable String address) {
		return deals.get(address);
	}

	/**
	 * Lists tracked deals.
	 *
	 * @return every tracked deal, newest first
	 */
	@GetMapping
	@Operation(summary = "All tracked deals, newest first")
	public List<TrackedDeal> list() {
		return deals.list();
	}

	/**
	 * @param programId Base58 address of the uptime_deal program
	 * @param oracle    Base58 key to pass as {@code oracle} to {@code create_deal}
	 * @param rpcUrl    the cluster this service settles on
	 */
	public record ConfigResponse(String programId, String oracle, String rpcUrl) {
	}

	/**
	 * @param address Base58 address of the on-chain deal
	 */
	public record RegisterRequest(@Schema(example = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin") String address) {
	}

}
