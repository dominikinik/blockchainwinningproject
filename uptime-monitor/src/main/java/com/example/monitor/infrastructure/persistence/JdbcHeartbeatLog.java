package com.example.monitor.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import com.example.monitor.domain.HealthCheckResult.Outcome;
import com.example.monitor.domain.heartbeat.Heartbeat;
import com.example.monitor.domain.heartbeat.Heartbeat.Report;
import com.example.monitor.domain.heartbeat.HeartbeatLog;

/** {@link HeartbeatLog} on the {@code deal_heartbeat} table of the {@code monitor-db} module. */
public class JdbcHeartbeatLog implements HeartbeatLog {

	private static final String COLUMNS = "id, deal_address, round_no, checked_at, outcome, http_status, detail, "
			+ "latency_ms, report, report_error, signature";

	private final JdbcClient jdbc;

	public JdbcHeartbeatLog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Heartbeat append(Heartbeat h) {
		KeyHolder keys = new GeneratedKeyHolder();
		jdbc.sql("INSERT INTO deal_heartbeat (deal_address, round_no, checked_at, outcome, http_status, detail, "
				+ "latency_ms, report, report_error, signature) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
			.params(h.dealAddress(), h.round(), Timestamp.from(h.checkedAt()), h.outcome().name(), h.httpStatus(),
					h.detail(), h.latencyMs(), h.report().name(), h.reportError(), h.signature())
			.update(keys, "id");
		return h.withId(keys.getKey().longValue());
	}

	@Override
	public void update(Heartbeat h) {
		int rows = jdbc.sql("UPDATE deal_heartbeat SET report = ?, report_error = ?, signature = ? WHERE id = ?")
			.params(h.report().name(), h.reportError(), h.signature(), h.id())
			.update();
		if (rows == 0) {
			throw new NoSuchElementException("Heartbeat " + h.id() + " is not logged");
		}
	}

	@Override
	public List<Heartbeat> recent(int limit) {
		return List.copyOf(jdbc.sql("SELECT " + COLUMNS + " FROM deal_heartbeat ORDER BY checked_at DESC, id DESC LIMIT ?")
			.param(limit)
			.query((rs, n) -> map(rs))
			.list());
	}

	@Override
	public List<Heartbeat> forDeal(String dealAddress, int limit) {
		return List.copyOf(jdbc.sql("SELECT " + COLUMNS
				+ " FROM deal_heartbeat WHERE deal_address = ? ORDER BY checked_at DESC, id DESC LIMIT ?")
			.params(dealAddress, limit)
			.query((rs, n) -> map(rs))
			.list());
	}

	private static Heartbeat map(ResultSet rs) throws SQLException {
		return new Heartbeat(rs.getLong("id"), rs.getString("deal_address"), rs.getInt("round_no"),
				rs.getTimestamp("checked_at").toInstant(), Outcome.valueOf(rs.getString("outcome")),
				rs.getObject("http_status", Integer.class), rs.getString("detail"), rs.getLong("latency_ms"),
				Report.valueOf(rs.getString("report")), rs.getString("report_error"), rs.getString("signature"));
	}

}
