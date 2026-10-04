package com.example.monitor.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.monitor.domain.ServiceId;
import com.example.monitor.domain.TrackingEvent;
import com.example.monitor.domain.TrackingEvent.Downtime;
import com.example.monitor.domain.TrackingEvent.TrackingStarted;
import com.example.monitor.domain.TrackingEventStore;
import com.example.monitor.domain.TrackingEventStore.ConcurrencyException;
import com.zaxxer.hikari.HikariDataSource;

/** Runs the store contract on a fresh in-memory H2 database (PostgreSQL mode) with the monitor-db schema. */
class JdbcTrackingEventStoreTest extends TrackingEventStoreContract {

	JdbcClient jdbc;

	HikariDataSource dataSource;

	/**
	 * A pool, as in production: H2 binds a CHECK constraint to the session that created it, so the
	 * connection that ran the schema must stay open (a non-pooling DataSource closes it at once).
	 */
	@Override
	TrackingEventStore newStore() {
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl("jdbc:h2:mem:store_" + UUID.randomUUID() + ";MODE=PostgreSQL");
		dataSource.setUsername("sa");
		dataSource.setMinimumIdle(2);
		new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
		jdbc = JdbcClient.create(dataSource);
		return new JdbcTrackingEventStore(jdbc,
				new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
	}

	@AfterEach
	void closePool() {
		dataSource.close();
	}

	@Test
	void writesOneRowPerEventNumberedFromOne() {
		store.append(a, 0, List.of(new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0),
				new Downtime(a, 404, "HTTP 404", T0)));

		assertThat(jdbc.sql("SELECT version FROM tracking_event WHERE service_id = ? ORDER BY version")
			.param(a.value())
			.query(Long.class)
			.list()).containsExactly(1L, 2L);
	}

	@Test
	void concurrentAppendsOfTheSameVersionLetExactlyOneWin() throws Exception {
		store.append(a, 0, List.of(new TrackingStarted(a, "http://a", Duration.ofSeconds(2), T0)));
		int writers = 8;
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(writers)) {
			List<Future<Boolean>> results = new java.util.ArrayList<>();
			for (int i = 0; i < writers; i++) {
				int status = 500 + i;
				results.add(executor.submit(() -> {
					start.await();
					try {
						store.append(a, 1, List.of(new Downtime(a, status, "", T0)));
						return true;
					}
					catch (ConcurrencyException e) {
						return false;
					}
				}));
			}
			start.countDown();
			long winners = 0;
			for (Future<Boolean> result : results) {
				winners += result.get() ? 1 : 0;
			}
			assertThat(winners).isEqualTo(1);
		}
		assertThat(store.load(a)).hasSize(2);
	}

	@Test
	void unknownTypeInTheTableFailsLoudly() {
		jdbc.sql("ALTER TABLE tracking_event DROP CONSTRAINT IF EXISTS " + typeCheckName()).update();
		jdbc.sql("INSERT INTO tracking_event (service_id, version, type, occurred_at) VALUES (?, 1, 'Bogus', ?)")
			.params(a.value(), java.sql.Timestamp.from(T0))
			.update();

		assertThatThrownBy(() -> store.load(a)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Bogus");
	}

	private String typeCheckName() {
		return jdbc.sql("SELECT constraint_name FROM information_schema.check_constraints "
				+ "WHERE check_clause LIKE '%TrackingStarted%'").query(String.class).single();
	}


}
