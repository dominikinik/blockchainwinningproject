package com.example.uptime.uptime;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UptimeRecordRepository extends JpaRepository<UptimeRecord, Instant> {

	List<UptimeRecord> findByTimestampBetweenOrderByTimestamp(Instant from, Instant to);

}
