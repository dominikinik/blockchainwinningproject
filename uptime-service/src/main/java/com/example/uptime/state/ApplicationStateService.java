package com.example.uptime.state;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

/**
 * Logical "up/down" switch for the service. Stopping does not shut the process down; it only makes
 * the health endpoint report DOWN, which in turn is what gets sampled and recorded.
 */
@Service
public class ApplicationStateService {

	private final AtomicBoolean up = new AtomicBoolean(true);

	public boolean isUp() {
		return up.get();
	}

	public void start() {
		up.set(true);
	}

	public void stop() {
		up.set(false);
	}

}
