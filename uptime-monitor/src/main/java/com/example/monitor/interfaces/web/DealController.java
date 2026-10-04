package com.example.monitor.interfaces.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.example.monitor.application.DealService;
import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.UptimeDeal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/deals")
@Tag(name = "Uptime deals", description = "On-chain uptime deals that this monitor settles as their oracle")
public class DealController {

	private final ObjectProvider<DealService> deals;

	public DealController(ObjectProvider<DealService> deals) {
		this.deals = deals;
	}

	@GetMapping("/config")
	@Operation(summary = "Program and oracle to use when creating a deal")
	public DealService.Config config() {
		return deals().config();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Settle a deal from the relayed service's health results",
			description = "The deal must exist on chain and name this monitor's oracle. A proposal is tracked as "
					+ "PROPOSED until its recipient accepts it on chain; the window then runs from the acceptance, read "
					+ "from the deal account. Each health check's UP/DOWN observation is sent directly to the chain. "
					+ "The contract decides the payout from its counters; anyone may trigger settlement after expiry. "
					+ "Deal rounds must match this monitor's sampling interval. Without "
					+ "serviceId the deal is measured against the relayed service (the only one accepted). 400 for an invalid request or deal, "
					+ "409 if already registered, 502 if the RPC node fails, 503 if the blockchain is disabled.")
	public DealResponse register(@RequestBody RegisterRequest request) {
		ServiceId service = request.serviceId() == null ? null : new ServiceId(request.serviceId());
		return DealResponse.of(deals().register(request.address(), service));
	}

	@GetMapping("/{address}")
	@Operation(summary = "One deal")
	public DealResponse get(@PathVariable String address) {
		return DealResponse.of(deals().get(address));
	}

	@GetMapping
	@Operation(summary = "All deals, most recently proposed first")
	public List<DealResponse> list() {
		return deals().list().stream().map(DealResponse::of).toList();
	}

	private DealService deals() {
		DealService service = deals.getIfAvailable();
		if (service == null) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"The deal oracle is off (monitor.blockchain.enabled=false)");
		}
		return service;
	}

	/**
	 * @param address   Base58 address of the on-chain deal
	 * @param serviceId the service the deal pays for; omit it, or send the relayed service's id
	 */
	public record RegisterRequest(@Schema(example = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin") String address,
			UUID serviceId) {
	}

	/**
	 * Same shape as the deal JSON the frontend reads, plus {@code serviceId}. {@code startsAt}/{@code endsAt} are
	 * {@code null} while the deal is {@code PROPOSED}.
	 */
	public record DealResponse(String address, UUID serviceId, String payer, String recipient, long amountLamports,
			long guaranteeLamports, long durationSeconds, Instant acceptDeadline, Instant startsAt, Instant endsAt,
			UptimeDeal.Status status, Long upChecks, Long totalRounds, Boolean paidToRecipient, String signature,
			Instant sentAt, int attempts, String error) {

		static DealResponse of(UptimeDeal d) {
			return new DealResponse(d.address(), d.serviceId().value(), d.payer(), d.recipient(), d.amountLamports(),
					d.guaranteeLamports(), d.durationSeconds(), d.acceptDeadline(), d.startsAt(), d.endsAt(), d.status(),
					d.upChecks(), d.totalRounds(), d.paidToRecipient(), d.signature(), d.sentAt(), d.attempts(),
					d.error());
		}

	}

}
