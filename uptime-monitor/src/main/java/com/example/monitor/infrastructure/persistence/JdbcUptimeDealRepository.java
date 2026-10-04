package com.example.monitor.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.domain.deal.UptimeDeal;
import com.example.monitor.domain.deal.UptimeDeal.Status;
import com.example.monitor.domain.deal.UptimeDealRepository;

/** {@link UptimeDealRepository} on the {@code uptime_deal} table of the {@code monitor-db} module. */
public class JdbcUptimeDealRepository implements UptimeDealRepository {

	private static final String COLUMNS = "address, service_id, payer, recipient, amount_lamports, "
			+ "guarantee_lamports, duration_seconds, accept_deadline, starts_at, status, up_seconds, total_seconds, "
			+ "paid_to_recipient, signature, sent_at, attempts, error, registered_at";

	private final JdbcClient jdbc;

	public JdbcUptimeDealRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void add(UptimeDeal deal) {
		try {
			jdbc.sql("INSERT INTO uptime_deal (" + COLUMNS
					+ ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
				.params(deal.address(), deal.serviceId().value(), deal.payer(), deal.recipient(), deal.amountLamports(),
						deal.guaranteeLamports(), deal.durationSeconds(), ts(deal.acceptDeadline()), ts(deal.startsAt()),
						deal.status().name(), deal.upSeconds(), deal.totalSeconds(), deal.paidToRecipient(),
						deal.signature(), ts(deal.sentAt()), deal.attempts(), deal.error(), ts(deal.registeredAt()))
				.update();
		}
		catch (DuplicateKeyException e) {
			throw new DealAlreadyRegisteredException(deal.address());
		}
	}

	@Override
	public void update(UptimeDeal deal) {
		int rows = jdbc.sql("UPDATE uptime_deal SET starts_at = ?, status = ?, up_seconds = ?, total_seconds = ?, "
				+ "paid_to_recipient = ?, signature = ?, sent_at = ?, attempts = ?, error = ? WHERE address = ?")
			.params(ts(deal.startsAt()), deal.status().name(), deal.upSeconds(), deal.totalSeconds(),
					deal.paidToRecipient(), deal.signature(), ts(deal.sentAt()), deal.attempts(), deal.error(),
					deal.address())
			.update();
		if (rows == 0) {
			throw new NoSuchElementException("Deal " + deal.address() + " is not registered");
		}
	}

	@Override
	public Optional<UptimeDeal> find(String address) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM uptime_deal WHERE address = ?")
			.param(address)
			.query((rs, n) -> map(rs))
			.optional();
	}

	@Override
	public List<UptimeDeal> findAll() {
		return query("SELECT " + COLUMNS + " FROM uptime_deal ORDER BY accept_deadline DESC, address");
	}

	@Override
	public List<UptimeDeal> findUnfinished() {
		return query("SELECT " + COLUMNS
				+ " FROM uptime_deal WHERE status IN ('PROPOSED', 'ACTIVE') ORDER BY accept_deadline, address");
	}

	@Override
	public List<UptimeDeal> findActive(ServiceId serviceId) {
		return List.copyOf(jdbc.sql("SELECT " + COLUMNS
				+ " FROM uptime_deal WHERE status = 'ACTIVE' AND service_id = ? ORDER BY starts_at, address")
			.param(serviceId.value())
			.query((rs, n) -> map(rs))
			.list());
	}

	private List<UptimeDeal> query(String sql) {
		return List.copyOf(jdbc.sql(sql).query((rs, n) -> map(rs)).list());
	}

	private static Timestamp ts(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	private static UptimeDeal map(ResultSet rs) throws SQLException {
		return new UptimeDeal(rs.getString("address"), new ServiceId(rs.getObject("service_id", UUID.class)),
				rs.getString("payer"), rs.getString("recipient"), rs.getLong("amount_lamports"),
				rs.getLong("guarantee_lamports"), rs.getLong("duration_seconds"), instant(rs, "accept_deadline"),
				instant(rs, "starts_at"), Status.valueOf(rs.getString("status")),
				rs.getObject("up_seconds", Long.class), rs.getObject("total_seconds", Long.class),
				rs.getObject("paid_to_recipient", Boolean.class), rs.getString("signature"), instant(rs, "sent_at"),
				rs.getInt("attempts"), rs.getString("error"), instant(rs, "registered_at"));
	}

}
