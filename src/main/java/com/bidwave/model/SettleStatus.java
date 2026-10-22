package com.bidwave.model;

/**
 * Outcome of settling a bid against a lot.
 */
public enum SettleStatus {
    ACCEPTED,
    REJECTED_LOW,
    REJECTED_CLOSED,
    DUPLICATE
}
