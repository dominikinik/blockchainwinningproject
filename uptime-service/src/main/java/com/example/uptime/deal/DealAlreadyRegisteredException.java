package com.example.uptime.deal;

/** The deal is already tracked by this service; mapped to HTTP 409. */
public class DealAlreadyRegisteredException extends RuntimeException {

	public DealAlreadyRegisteredException(String address) {
		super("Deal " + address + " is already registered");
	}

}
