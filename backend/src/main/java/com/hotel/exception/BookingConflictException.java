package com.hotel.exception;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

public class BookingConflictException extends RuntimeException {
    private final List<LocalDate> conflictingDates;

    public BookingConflictException(String message) {
        super(message);
        this.conflictingDates = Collections.emptyList();
    }

    public BookingConflictException(String message, List<LocalDate> conflictingDates) {
        super(message);
        this.conflictingDates = conflictingDates != null ? conflictingDates : Collections.emptyList();
    }

    public List<LocalDate> getConflictingDates() {
        return conflictingDates;
    }
}
