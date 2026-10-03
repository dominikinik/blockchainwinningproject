package com.example.uptime.aggregation.infrastructure.persistence;

import java.time.Instant;
import java.util.List;


import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Persistence representation of a completed second of observations. */
@Entity
@Table(name = "uptime_record")
public class UptimeRecord {

	/** Start of the second (UTC, truncated to seconds). */
	@Id
	@Column(name = "ts")
	private Instant timestamp;

	/** True only if every sample taken during the second was UP. */
	private boolean up;

	private int samples;

	private int upSamples;

	@Column(name = "partial_coverage")
	private Boolean partialCoverage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "failures", columnDefinition = "jsonb")
	private List<Map<String, Object>> failures;

	protected UptimeRecord() {
	}

	public UptimeRecord(Instant timestamp, boolean up, int samples, int upSamples) {
		this.timestamp = timestamp;
		this.up = up;
		this.samples = samples;
		this.upSamples = upSamples;
	}



	/** Null means this legacy row has no coverage details. */
	public Boolean getPartialCoverage() {
		return partialCoverage;
	}

	/** Null means details unavailable; an empty list means no observed failures. */
	public List<Map<String, Object>> getFailures() {
		return failures == null ? null : List.copyOf(failures);
	}

	public Instant getTimestamp() {
		return timestamp;
	}

	public boolean isUp() {
		return up;
	}

	public int getSamples() {
		return samples;
	}

	public int getUpSamples() {
		return upSamples;
	}

}
