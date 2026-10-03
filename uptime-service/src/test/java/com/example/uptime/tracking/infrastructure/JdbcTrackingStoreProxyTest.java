package com.example.uptime.tracking.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import com.example.uptime.aggregation.infrastructure.persistence.UptimeJsonConfiguration.PersistenceJson;
import com.example.uptime.tracking.application.TrackingStore;
import com.example.uptime.tracking.domain.TrackingSession;
import com.example.uptime.tracking.domain.TrackingStatus;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Exercises the production event-listener method through an interface transaction proxy, without PostgreSQL. */
@SuppressWarnings("unchecked")
class JdbcTrackingStoreProxyTest {
	@Test
	void startupRecoveryRunsThroughTransactionalInterfaceProxy() throws Exception {
		try (var context = new AnnotationConfigApplicationContext(Config.class)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			TrackingSession session = new TrackingSession(UUID.randomUUID(), Instant.parse("2100-01-01T00:00:00.123456789Z"),
					null, TrackingStatus.ACTIVE, null);
			assertThat(AopUtils.isJdkDynamicProxy(context.getBean(TrackingStore.class))).isTrue();
			when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE status"), any(RowMapper.class)))
					.thenAnswer(invocation -> {
						assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
						return List.of(session);
					});
			when(jdbc.update(startsWith("INSERT INTO tracking_event"), any(Object[].class))).thenReturn(1);
			publishReady(context);
			verify(jdbc).update("UPDATE tracking_session SET status = 'INTERRUPTED', stopped_at = ?, stop_second = ? WHERE id = ?",
					session.startedAt().toString(), session.startedAt().getEpochSecond(), session.id());
			verify(context.getBean(Connection.class)).commit();
			verify(context.getBean(Connection.class), never()).rollback();
		}
	}

	@Test
	void startupListenerFailureRollsBackSessionAndStopEventTransaction() throws Exception {
		try (var context = new AnnotationConfigApplicationContext(Config.class)) {
			JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
			TrackingSession session = new TrackingSession(UUID.randomUUID(), Instant.parse("2100-01-01T00:00:00.123456789Z"),
					null, TrackingStatus.ACTIVE, null);
			when(jdbc.query(startsWith("SELECT * FROM tracking_session WHERE status"), any(RowMapper.class))).thenReturn(List.of(session));
			when(jdbc.query(startsWith("SELECT payload::text FROM tracking_event"), any(RowMapper.class), any(Object[].class)))
					.thenThrow(new DataAccessResourceFailureException("Injected event read failure"));
			assertThatThrownBy(() -> publishReady(context)).isInstanceOf(DataAccessResourceFailureException.class);
			verify(context.getBean(Connection.class)).rollback();
			verify(context.getBean(Connection.class), never()).commit();
		}
	}

	private static void publishReady(AnnotationConfigApplicationContext context) {
		context.publishEvent(new ApplicationReadyEvent(mock(SpringApplication.class), new String[0], context, Duration.ZERO));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement(proxyTargetClass = false)
	static class Config {
		@Bean(destroyMethod = "") Connection connection() { return mock(Connection.class); }
		@Bean DataSource dataSource(Connection connection) throws SQLException {
			DataSource source = mock(DataSource.class);
			when(source.getConnection()).thenReturn(connection);
			when(connection.getAutoCommit()).thenReturn(true);
			return source;
		}
		@Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
		@Bean JdbcTemplate jdbc() { return mock(JdbcTemplate.class); }
		@Bean TrackingStore tracking(JdbcTemplate jdbc) {
			return new JdbcTrackingStore(jdbc, new PersistenceJson(JsonMapper.builder().build()));
		}
	}
}
