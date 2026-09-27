package com.hotel.exception;

public class BookingValidationException extends RuntimeException {
    public BookingValidationException(String message) {
        super(message);
    }
}
