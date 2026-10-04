package com.example.monitor.domain.deal;

public class DealAlreadyRegisteredException extends RuntimeException {

	public DealAlreadyRegisteredException(String address) {
		super("Deal " + address + " is already registered");
	}

}
