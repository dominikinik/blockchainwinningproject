package com.example.monitor.infrastructure.persistence;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import com.example.monitor.domain.deal.UptimeDealRepository;
import com.zaxxer.hikari.HikariDataSource;

/** The contract on in-memory H2 (PostgreSQL mode) with the monitor-db schema, behind a pool (see JdbcTrackingEventStoreTest). */
class JdbcUptimeDealRepositoryTest extends UptimeDealRepositoryContract {

	HikariDataSource dataSource;

	@Override
	UptimeDealRepository newRepository() {
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl("jdbc:h2:mem:deals_" + UUID.randomUUID() + ";MODE=PostgreSQL");
		dataSource.setUsername("sa");
		new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
		return new JdbcUptimeDealRepository(JdbcClient.create(dataSource));
	}

	@AfterEach
	void closePool() {
		dataSource.close();
	}

}
