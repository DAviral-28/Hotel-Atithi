package com.hotel.exception;

/** Missing, wrong or expired staff credentials (HTTP 401). */
public class StaffUnauthorizedException extends RuntimeException {
    public StaffUnauthorizedException(String message) {
        super(message);
    }
}
