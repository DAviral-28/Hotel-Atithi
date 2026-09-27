package com.hotel;

import com.hotel.controller.GlobalExceptionHandler;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.exception.StaffUnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 3 (B6): validation failures become a clean, readable 400 that names each field and its problem. */
class GlobalExceptionHandlerTest {

    @SuppressWarnings("unused")
    private void endpoint(HoldBookingRequest request) {
    }

    @Test
    @DisplayName("MethodArgumentNotValidException -> 400 VALIDATION_FAILED listing every field and issue")
    void validationFailureIsReadable() throws Exception {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "holdBookingRequest");
        binding.addError(new FieldError("holdBookingRequest", "guestPhone", "Enter a valid 10-digit Indian mobile number (starting with 6, 7, 8 or 9)"));
        binding.addError(new FieldError("holdBookingRequest", "guestName", "Guest name must be 2 to 80 characters"));
        binding.addError(new FieldError("holdBookingRequest", "guestName", "Guest name is required"));
        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("endpoint", HoldBookingRequest.class), 0);

        ResponseEntity<Map<String, Object>> response =
                new GlobalExceptionHandler().handleMethodArgumentNotValid(new MethodArgumentNotValidException(parameter, binding));

        assertEquals(400, response.getStatusCode().value());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals("VALIDATION_FAILED", body.get("error"));
        String message = (String) body.get("message");
        assertTrue(message.contains("guestPhone: Enter a valid 10-digit Indian mobile number"), message);
        assertTrue(message.contains("guestName: Guest name must be 2 to 80 characters"), message);
        @SuppressWarnings("unchecked")
        Map<String, String> fields = (Map<String, String>) body.get("fieldErrors");
        assertEquals(2, fields.size());
        assertTrue(fields.get("guestName").contains("Guest name is required"), "multiple issues on one field are all reported");
        assertNotNull(body.get("timestamp"));
    }

    @Test
    @DisplayName("Phase 4: StaffUnauthorizedException -> HTTP 401 UNAUTHORIZED with a readable message")
    void staffUnauthorizedMapsTo401() {
        ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                .handleStaffUnauthorized(new StaffUnauthorizedException("Staff sign-in required."));
        assertEquals(401, response.getStatusCode().value());
        assertEquals("UNAUTHORIZED", response.getBody().get("error"));
        assertEquals("Staff sign-in required.", response.getBody().get("message"));
        assertNotNull(response.getBody().get("timestamp"));
    }
}
