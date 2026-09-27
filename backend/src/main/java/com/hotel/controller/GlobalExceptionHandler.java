package com.hotel.controller;

import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingForbiddenException;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.exception.StaffUnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(BookingConflictException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "CONFLICT");
        body.put("message", ex.getMessage());
        body.put("conflictingDates", ex.getConflictingDates());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(BookingValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(BookingValidationException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(BookingForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(BookingForbiddenException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "FORBIDDEN");
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(StaffUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleStaffUnauthorized(StaffUnauthorizedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "UNAUTHORIZED");
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(BookingNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "NOT_FOUND");
        body.put("message", ex.getMessage());
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Bean Validation failures on request bodies: 400 with a readable summary ("guestPhone: Enter a valid
     * ...") and the same information per field so the UI can highlight inputs.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.merge(error.getField(), String.valueOf(error.getDefaultMessage()), (a, b) -> a + "; " + b);
        }
        return validationFailed(fieldErrors);
    }

    /** Validation on query / path parameters (@Validated controllers). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String path = v.getPropertyPath().toString();
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            fieldErrors.merge(field, v.getMessage(), (a, b) -> a + "; " + b);
        }
        return validationFailed(fieldErrors);
    }

    /** Broken JSON, wrong types, unparseable dates or unknown enum values: a clean 400, never a stack trace. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest("The request could not be read. Please check the values you entered (dates use YYYY-MM-DD) and try again.");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest(ex.getParameterName() + ": this value is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest(ex.getName() + ": this value is not valid");
    }

    private ResponseEntity<Map<String, Object>> validationFailed(Map<String, String> fieldErrors) {
        String summary = fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "VALIDATION_FAILED");
        body.put("message", summary.isEmpty() ? "The request is not valid." : summary);
        body.put("fieldErrors", fieldErrors);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", message);
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * DynamoDB throttled the request (free-tier tables have tiny capacity). Tell the client to retry
     * rather than surfacing a generic 500, so the UI can show a proper "try again" state instead of
     * guessing.
     */
    @ExceptionHandler({ProvisionedThroughputExceededException.class, RequestLimitExceededException.class})
    public ResponseEntity<Map<String, Object>> handleThrottled(DynamoDbException ex) {
        log.warn("DynamoDB throttled a request: {}", ex.getMessage());
        return serviceBusy();
    }

    /** Another transaction is touching the same inventory rows right now: a genuine booking race. */
    @ExceptionHandler(TransactionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleTransactionConflict(TransactionConflictException ex) {
        log.warn("Concurrent DynamoDB transaction conflict: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "CONFLICT");
        body.put("message", "Someone else is booking this room at this moment. Please try again in a few seconds.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Any other DynamoDB failure; throttling reported through the generic error code is still a 503. */
    @ExceptionHandler(DynamoDbException.class)
    public ResponseEntity<Map<String, Object>> handleDynamoDb(DynamoDbException ex) {
        if (ex.isThrottlingException()) {
            log.warn("DynamoDB throttled a request: {}", ex.getMessage());
            return serviceBusy();
        }
        log.error("Unexpected DynamoDB failure", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "INTERNAL_ERROR");
        body.put("message", "We could not complete your request right now. Please try again or call the front desk.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private ResponseEntity<Map<String, Object>> serviceBusy() {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "SERVICE_BUSY");
        body.put("message", "We're receiving a lot of requests right now. Please try again in a few seconds.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5").body(body);
    }

    /**
     * Thrown by PiiEncryptionService / BookingTokenService when a JWE ciphertext or JWS
     * signature can't be decrypted/verified — most likely because the encryption or signing
     * key was rotated after this record was written. Never leak the raw exception (it can
     * reference cryptographic internals) or a stack trace to the guest; log it server-side
     * with the real detail and give a generic, actionable message instead of Spring's default
     * unhandled-exception response.
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException ex) {
        log.error("Security/crypto failure while processing a request: {}", ex.getMessage(), ex);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "INTERNAL_SECURITY_ERROR");
        body.put("message", "This booking record could not be processed right now. Please contact the front desk for assistance.");
        body.put("timestamp", Instant.now().toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
