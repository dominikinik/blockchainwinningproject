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
	@Operation(summary = "Settle a deal from the health of a service",
			description = "The deal must exist on chain and name this monitor's oracle. A proposal is tracked as "
					+ "PROPOSED until its recipient accepts it on chain; the window then runs from the acceptance, read "
					+ "from the deal account. The monitor starts checking healthUrl for this deal alone (under the "
					+ "deal's serviceId) when the deal is accepted and stops once it is settled, cancelled or failed. "
					+ "The deal closes early (refund) when a failure makes more than 99% uptime unreachable, settles "
					+ "when that tracking is finished by hand, and otherwise settles when the window ends. Without "
					+ "healthUrl the default service's endpoint is used. 400 for an invalid request or deal, "
					+ "409 if already registered, 502 if the RPC node fails, 503 if the blockchain is disabled.")
	public DealResponse register(@RequestBody RegisterRequest request) {
		return DealResponse.of(deals().register(request.address(), request.healthUrl()));
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
	 * @param healthUrl the health endpoint the deal pays for; omit for the default service's
	 */
	public record RegisterRequest(@Schema(example = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin") String address,
			@Schema(example = "http://localhost:8080/api/health") String healthUrl) {
	}

	/**
	 * Same shape as the deal JSON the frontend reads, plus {@code serviceId} (the deal's own tracked service, whose
	 * history is under {@code /api/subscriptions/{serviceId}}) and {@code healthUrl}. {@code startsAt}/{@code endsAt}
	 * are {@code null} while the deal is {@code PROPOSED}.
	 */
	public record DealResponse(String address, UUID serviceId, String healthUrl, String payer, String recipient, long amountLamports,
			long guaranteeLamports, long durationSeconds, Instant acceptDeadline, Instant startsAt, Instant endsAt,
			UptimeDeal.Status status, Long upSeconds, Long totalSeconds, Boolean paidToRecipient, String signature,
			Instant sentAt, int attempts, String error) {

		static DealResponse of(UptimeDeal d) {
			return new DealResponse(d.address(), d.serviceId().value(), d.healthUrl(), d.payer(), d.recipient(), d.amountLamports(),
					d.guaranteeLamports(), d.durationSeconds(), d.acceptDeadline(), d.startsAt(), d.endsAt(), d.status(),
					d.upSeconds(), d.totalSeconds(), d.paidToRecipient(), d.signature(), d.sentAt(), d.attempts(),
					d.error());
		}

	}

}
