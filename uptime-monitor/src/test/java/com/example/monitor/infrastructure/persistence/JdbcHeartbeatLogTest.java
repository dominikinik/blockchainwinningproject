package com.example.monitor.infrastructure.persistence;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.example.monitor.domain.heartbeat.HeartbeatLog;
import com.zaxxer.hikari.HikariDataSource;

/** The contract on in-memory H2 (PostgreSQL mode) with the monitor-db schema, behind a pool. */
class JdbcHeartbeatLogTest extends HeartbeatLogContract {

	HikariDataSource dataSource;

	@Override
	HeartbeatLog newLog() {
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl("jdbc:h2:mem:heartbeats_" + UUID.randomUUID() + ";MODE=PostgreSQL");
		dataSource.setUsername("sa");
		new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
		return new JdbcHeartbeatLog(JdbcClient.create(dataSource));
	}

	@AfterEach
	void closePool() {
		dataSource.close();
	}

}
