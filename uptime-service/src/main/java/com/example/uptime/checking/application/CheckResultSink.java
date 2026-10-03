package com.example.uptime.checking.application;

import com.example.uptime.checking.domain.CheckResult;

@FunctionalInterface
public interface CheckResultSink {
	void accept(CheckResult result);
}
