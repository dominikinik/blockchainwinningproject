package com.example.monitor.infrastructure.solana;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.monitor.domain.DowntimePublisher;
import com.example.monitor.domain.DowntimeReport;

/** {@link DowntimePublisher} used when {@code monitor.blockchain.enabled=false}: only logs the report. */
public class LoggingDowntimePublisher implements DowntimePublisher {

	private static final Logger log = LoggerFactory.getLogger(LoggingDowntimePublisher.class);

	@Override
	public void publish(DowntimeReport report) {
		log.info("Blockchain disabled, not publishing: {}", SolanaMemoDowntimePublisher.memo(report));
	}

}
