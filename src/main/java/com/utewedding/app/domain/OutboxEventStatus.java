package com.utewedding.app.domain;

public enum OutboxEventStatus { PENDING, PROCESSING, SUCCEEDED, FAILED, DEAD, AMBIGUOUS }