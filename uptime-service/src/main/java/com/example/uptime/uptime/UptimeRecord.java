package com.example.uptime.uptime;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row per second: whether the service was up for that whole second. */
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

	protected UptimeRecord() {
	}

	public UptimeRecord(Instant timestamp, boolean up, int samples, int upSamples) {
		this.timestamp = timestamp;
		this.up = up;
		this.samples = samples;
		this.upSamples = upSamples;
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
