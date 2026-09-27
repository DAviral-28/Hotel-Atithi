package com.hotel;

import com.hotel.dto.AccessBookingRequest;
import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.dto.LookupBookingRequest;
import com.hotel.dto.PayBalanceRequest;
import com.hotel.model.PaymentMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 (B6): Jakarta Bean Validation on the incoming DTOs, run through the real validator exactly
 * as Spring's @Valid does. (The compact constructors sanitise / canonicalise first, so these tests
 * also prove that "+91 94523-14397" is accepted and script tags are neutralised.)
 */
class InputValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate IN = LocalDate.of(2026, 10, 20);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 22);

    private static HoldBookingRequest hold(String name, String phone, String email) {
        return new HoldBookingRequest(101, IN, OUT, 2, name, phone, email);
    }

    private static Set<String> invalidFields(Object dto) {
        return VALIDATOR.validate(dto).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    private static String messageFor(Object dto, String field) {
        return VALIDATOR.validate(dto).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("a well-formed hold request has no violations, whatever the phone spelling")
    void validHoldRequest() {
        assertTrue(invalidFields(hold("Ravi Kumar", "9452314397", "ravi@example.com")).isEmpty());
        assertTrue(invalidFields(hold("Ravi Kumar", "+91 94523 14397", "ravi@example.com")).isEmpty());
        assertTrue(invalidFields(hold("Ravi Kumar", "09452314397", "ravi@example.com")).isEmpty());
        assertTrue(invalidFields(hold("Dr. Anne-Marie O'Neil", "94523-14397", "ravi@example.com")).isEmpty());
        assertTrue(invalidFields(hold("रवि कुमार", "9452314397", "ravi@example.com")).isEmpty(),
                "Hindi names are valid");
        assertEquals("9452314397", hold("Ravi Kumar", "+91 94523 14397", "ravi@example.com").guestPhone());
    }

    @Test
    @DisplayName("guest name: required, 2-80 characters, letters only, markup stripped")
    void guestNameRules() {
        assertEquals(Set.of("guestName"), invalidFields(hold(null, "9452314397", "r@example.com")));
        assertEquals(Set.of("guestName"), invalidFields(hold("   ", "9452314397", "r@example.com")));
        assertEquals(Set.of("guestName"), invalidFields(hold("A", "9452314397", "r@example.com")));
        assertTrue(invalidFields(hold("Al", "9452314397", "r@example.com")).isEmpty());
        assertTrue(invalidFields(hold("A".repeat(80), "9452314397", "r@example.com")).isEmpty());
        assertEquals(Set.of("guestName"), invalidFields(hold("A".repeat(81), "9452314397", "r@example.com")));
        assertEquals(Set.of("guestName"), invalidFields(hold("R2D2", "9452314397", "r@example.com")));
        assertEquals(Set.of("guestName"), invalidFields(hold("Ravi; DROP TABLE", "9452314397", "r@example.com")));

        // Tags are removed, then the remainder is validated.
        HoldBookingRequest scripted = hold("<script>alert(1)</script>Ravi", "9452314397", "r@example.com");
        assertEquals("Ravi", scripted.guestName());
        assertTrue(invalidFields(scripted).isEmpty());
        assertEquals(Set.of("guestName"), invalidFields(hold("<script>alert(1)</script>", "9452314397", "r@example.com")),
                "nothing left after stripping the script block => blank");
        assertEquals(Set.of("guestName"), invalidFields(hold("<b>x</b>", "9452314397", "r@example.com")),
                "a single letter is too short once the tags are gone");
    }

    @Test
    @DisplayName("guest phone: required and a valid Indian mobile number (^[6-9]\\d{9}$ after normalisation)")
    void guestPhoneRules() {
        for (String bad : new String[]{null, "", "  ", "12345", "5452314397", "94523143970", "abcdefghij", "+1 9452314397"}) {
            assertEquals(Set.of("guestPhone"), invalidFields(hold("Ravi Kumar", bad, "r@example.com")), "phone '" + bad + "'");
        }
        assertNotNull(messageFor(hold("Ravi Kumar", "12345", "r@example.com"), "guestPhone"));
        assertTrue(messageFor(hold("Ravi Kumar", "12345", "r@example.com"), "guestPhone").contains("10-digit"));
    }

    @Test
    @DisplayName("guest email: required, valid, at most 120 characters")
    void guestEmailRules() {
        assertEquals(Set.of("guestEmail"), invalidFields(hold("Ravi Kumar", "9452314397", null)));
        assertEquals(Set.of("guestEmail"), invalidFields(hold("Ravi Kumar", "9452314397", "  ")));
        assertEquals(Set.of("guestEmail"), invalidFields(hold("Ravi Kumar", "9452314397", "not-an-email")));
        String local = "a".repeat(60);
        assertTrue(invalidFields(hold("Ravi Kumar", "9452314397", local + "@" + "b".repeat(50) + ".com")).isEmpty(), "115 chars is fine");
        assertEquals(Set.of("guestEmail"), invalidFields(hold("Ravi Kumar", "9452314397", local + "@" + "b".repeat(63) + ".com")),
                "128 chars is over the 120 limit");
    }

    @Test
    @DisplayName("guest count and dates are still required")
    void otherHoldFields() {
        assertEquals(Set.of("guestCount"), invalidFields(new HoldBookingRequest(101, IN, OUT, 0, "Ravi Kumar", "9452314397", "r@example.com")));
        assertEquals(Set.of("guestCount"), invalidFields(new HoldBookingRequest(101, IN, OUT, 11, "Ravi Kumar", "9452314397", "r@example.com")));
        assertEquals(Set.of("roomNumber"), invalidFields(new HoldBookingRequest(null, IN, OUT, 2, "Ravi Kumar", "9452314397", "r@example.com")));
        assertEquals(Set.of("checkInDate"), invalidFields(new HoldBookingRequest(101, null, OUT, 2, "Ravi Kumar", "9452314397", "r@example.com")));
    }

    @Test
    @DisplayName("UTR: exactly 12 letters/digits when present (optional for pay-at-hotel)")
    void utrRules() {
        assertTrue(invalidFields(ConfirmBookingRequest.upi("402512345678", null)).isEmpty());
        assertTrue(invalidFields(ConfirmBookingRequest.upi("ab12CD345678", null)).isEmpty());
        assertTrue(invalidFields(ConfirmBookingRequest.payAtHotel(null)).isEmpty());
        for (String bad : new String[]{"12345678901", "1234567890123", "1234 5678 9012", "12345678901!", "abc-12345678"}) {
            assertEquals(Set.of("paymentReference"), invalidFields(ConfirmBookingRequest.upi(bad, null)), "UTR '" + bad + "'");
        }
        // paymentMethod has no @NotNull any more: the public "pay at hotel only" flow defaults a missing
        // one to PAY_AT_HOTEL (see ConfirmBookingRequest's compact constructor) instead of rejecting it.
        assertTrue(invalidFields(new ConfirmBookingRequest(null, null, null)).isEmpty());
        assertEquals(PaymentMethod.PAY_AT_HOTEL, new ConfirmBookingRequest(null, null, null).paymentMethod());
        assertEquals(Set.of("bookingToken"),
                invalidFields(new ConfirmBookingRequest(PaymentMethod.PAY_AT_HOTEL, null, "x".repeat(5000))));

        assertTrue(invalidFields(new PayBalanceRequest("402512345678")).isEmpty());
        assertEquals(Set.of("paymentReference"), invalidFields(new PayBalanceRequest(null)));
        assertEquals(Set.of("paymentReference"), invalidFields(new PayBalanceRequest("")));
        assertEquals(Set.of("paymentReference"), invalidFields(new PayBalanceRequest("12345")));
    }

    @Test
    @DisplayName("access request needs the FULL valid phone; lookup request needs a safe booking ID and a bounded proof")
    void accessAndLookupRules() {
        assertTrue(invalidFields(new AccessBookingRequest("+91 94523 14397")).isEmpty());
        assertEquals("9452314397", new AccessBookingRequest("+91 94523 14397").guestPhone());
        assertEquals(Set.of("guestPhone"), invalidFields(new AccessBookingRequest("4397")), "last 4 is not enough to access");
        assertEquals(Set.of("guestPhone"), invalidFields(new AccessBookingRequest("")));
        assertEquals(Set.of("guestPhone"), invalidFields(new AccessBookingRequest(null)));

        assertTrue(invalidFields(new LookupBookingRequest("3a7b1c2d-1111-2222-3333-444455556666", "4397")).isEmpty());
        assertEquals(Set.of("bookingId"), invalidFields(new LookupBookingRequest("../../etc/passwd", "4397")));
        assertEquals(Set.of("bookingId"), invalidFields(new LookupBookingRequest("", "4397")));
        assertEquals(Set.of("bookingId"), invalidFields(new LookupBookingRequest("a".repeat(65), "4397")));
        assertEquals(Set.of("phone"), invalidFields(new LookupBookingRequest("3a7b1c2d", "")));
        assertEquals(Set.of("phone"), invalidFields(new LookupBookingRequest("3a7b1c2d", "1".repeat(21))));
    }
}
