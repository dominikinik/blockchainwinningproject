package com.example.monitor.interfaces.web;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.monitor.domain.deal.DealAlreadyRegisteredException;
import com.example.monitor.infrastructure.solana.SolanaRpc.SolanaRpcException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(DealAlreadyRegisteredException.class)
	ProblemDetail dealConflict(DealAlreadyRegisteredException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(SolanaRpcException.class)
	ProblemDetail badGateway(SolanaRpcException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}

}
