package com.example.uptime.web;

import com.example.uptime.aggregation.application.OutsideTrackingCoverageException;
import com.example.uptime.tracking.application.TrackingConflictException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

}
