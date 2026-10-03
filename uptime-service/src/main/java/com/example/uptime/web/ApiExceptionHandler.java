package com.example.uptime.web;

import com.example.uptime.aggregation.application.OutsideTrackingCoverageException;
import com.example.uptime.tracking.application.TrackingConflictException;
import org.springframework.dao.DataAccessException;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.uptime.deal.DealAlreadyRegisteredException;
import com.example.uptime.solana.SolanaRpc.SolanaRpcException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(DataAccessException.class)
	ProblemDetail historyUnavailable(DataAccessException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
				"Uptime history is temporarily unavailable");
	}

	@ExceptionHandler(OutsideTrackingCoverageException.class)
	ProblemDetail outsideCoverage(OutsideTrackingCoverageException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(422), e.getMessage());
		problem.setProperty("code", "OUTSIDE_TRACKING_COVERAGE");
		problem.setProperty("from", e.from().toString());
		problem.setProperty("to", e.to().toString());
		return problem;
	}

	@ExceptionHandler(TrackingConflictException.class)
	ProblemDetail trackingConflict(TrackingConflictException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setProperty("code", "TRACKING_CONFLICT");
		return problem;
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail badRequest(IllegalArgumentException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
	}

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail notFound(NoSuchElementException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}

	@ExceptionHandler(DealAlreadyRegisteredException.class)
	ProblemDetail conflict(DealAlreadyRegisteredException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
	}

	@ExceptionHandler(SolanaRpcException.class)
	ProblemDetail badGateway(SolanaRpcException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
	}

}
