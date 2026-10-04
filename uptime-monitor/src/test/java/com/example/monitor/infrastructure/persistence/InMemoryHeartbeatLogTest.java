package com.example.monitor.infrastructure.persistence;

import com.example.monitor.domain.heartbeat.HeartbeatLog;
import com.example.monitor.support.InMemoryHeartbeatLog;

class InMemoryHeartbeatLogTest extends HeartbeatLogContract {

	@Override
	HeartbeatLog newLog() {
		return new InMemoryHeartbeatLog();
	}

}
