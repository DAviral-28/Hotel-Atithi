package com.hotel.exception;

/** The caller could not prove they own the booking (missing / wrong / expired manage token, wrong phone). HTTP 403. */
public class BookingForbiddenException extends RuntimeException {
    public BookingForbiddenException(String message) {
        super(message);
    }
}
