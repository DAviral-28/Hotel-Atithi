package com.hotel;

import com.hotel.dto.AccessBookingRequest;
import com.hotel.dto.BookingResponse;
import com.hotel.dto.BookingSummaryResponse;
import com.hotel.dto.ConfirmBookingRequest;
import com.hotel.dto.HoldBookingRequest;
import com.hotel.dto.LookupBookingRequest;
import com.hotel.dto.VerifiedBookingSummary;
import com.hotel.exception.BookingForbiddenException;
import com.hotel.exception.BookingNotFoundException;
import com.hotel.exception.BookingValidationException;
import com.hotel.security.BookingTokenService;
import com.hotel.security.PiiEncryptionService;
import com.hotel.service.BookingService;
import com.hotel.util.CorsOrigins;
import com.hotel.util.InputSanitizer;
import com.hotel.util.PhoneNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3: canonical phone normalisation (B3), HMAC blind index (B2), masked / two-factor lookup (B1),
 * markup sanitising and CORS origin parsing.
 */
class Phase3SecurityTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KEY = "test-secret-encryption-key-32b!";
    private static final String PEPPER = "a-long-random-pepper-for-tests-0123456789";

    private BookingConcurrencyTest.InMemoryBookingRepository repository;
    private BookingService service;

    @BeforeEach
    void setUp() {
        repository = new BookingConcurrencyTest.InMemoryBookingRepository();
        BookingConcurrencyTest.MutableClock clock =
                new BookingConcurrencyTest.MutableClock(Instant.parse("2026-10-01T10:00:00Z"), IST);
        service = new BookingService(repository, clock, new BookingTokenService(""));
    }

    private static final LocalDate IN = LocalDate.of(2026, 10, 20);
    private static final LocalDate OUT = LocalDate.of(2026, 10, 22);

    private BookingResponse hold(int room, String name, String phone) {
        return service.holdRoom(new HoldBookingRequest(room, IN, OUT, 2, name, phone, "ravi.kumar@example.com"));
    }

    private BookingResponse confirmed(int room, String name, String phone) {
        BookingResponse h = hold(room, name, phone);
        return service.confirmBooking(h.bookingId(), ConfirmBookingRequest.payAtHotel(h.bookingToken()));
    }

    // ============================================================ B3: PhoneNormalizer

    @Test
    @DisplayName("B3: every common spelling of one number normalises to the same 10 digits")
    void allSpellingsResolveToTheSameNumber() {
        for (String spelling : new String[]{
                "+91 94523 14397", "09452314397", "94523-14397", "9452314397",
                "(+91) 94523 14397", "0091 9452314397", "91-94523-14397", "  9452314397  ",
                "+91-(945) 231-4397", "94523.14397", "919452314397", "+919452314397"}) {
            assertEquals("9452314397", PhoneNormalizer.require(spelling), "spelling: '" + spelling + "'");
        }
    }

    @Test
    @DisplayName("B3: invalid phone strings are rejected with a 400 before any lookup or hashing")
    void invalidPhonesAreRejected() {
        for (String bad : new String[]{
                null, "", "   ", "12345", "5452314397", "0452314397", "94523143970", "945231439",
                "+9452314397", "+1 9452314397", "+91 5452314397", "abcdefghij", "9452314397x",
                "94523 14397 12", "+91+9452314397", "9452<script>", "00000000000", "1234567890"}) {
            assertTrue(PhoneNormalizer.tryNormalize(bad).isEmpty(), "must be invalid: '" + bad + "'");
            assertThrows(BookingValidationException.class, () -> PhoneNormalizer.require(bad), "'" + bad + "'");
        }
        assertTrue(PhoneNormalizer.CANONICAL.matcher("6123456789").matches());
        assertFalse(PhoneNormalizer.CANONICAL.matcher("5123456789").matches());
    }

    @Test
    @DisplayName("B3: last-4 / masking / stored-phone helpers")
    void phoneHelpers() {
        assertTrue(PhoneNormalizer.isLast4("4397"));
        assertTrue(PhoneNormalizer.isLast4(" 4397 "));
        assertFalse(PhoneNormalizer.isLast4("439"));
        assertFalse(PhoneNormalizer.isLast4("43978"));
        assertFalse(PhoneNormalizer.isLast4("43a7"));
        assertEquals("4397", PhoneNormalizer.last4("9452314397"));
        assertEquals("******4397", PhoneNormalizer.mask("+91 94523 14397"));
        assertEquals("9812345671", PhoneNormalizer.forStored("+919812345671"), "legacy stored spelling");
        assertEquals("9812345671", PhoneNormalizer.forStored("9812345671"));
        assertEquals(List.of("9452314397", "+919452314397", "919452314397", "09452314397"),
                PhoneNormalizer.legacySpellings("9452314397"));
    }

    @Test
    @DisplayName("B3: hold stores ONE canonical phone whatever the spelling, and all spellings find the same guest")
    void holdStoresCanonicalPhone() {
        BookingResponse a = hold(101, "Ravi Kumar", "+91 94523 14397");
        BookingResponse b = hold(102, "Ravi Kumar", "09452314397");
        BookingResponse c = hold(103, "Ravi Kumar", "94523-14397");
        for (BookingResponse r : List.of(a, b, c)) {
            assertEquals("9452314397", repository.findBookingById(r.bookingId()).orElseThrow().guestPhone());
        }
        for (String spelling : new String[]{"9452314397", "+91 94523 14397", "094523-14397"}) {
            assertEquals(3, service.lookupSummariesByPhone(spelling).size(), spelling);
        }
    }

    @Test
    @DisplayName("B3: an invalid phone on hold is a 400 and locks nothing")
    void invalidPhoneOnHoldLocksNothing() {
        assertThrows(BookingValidationException.class, () -> hold(104, "Ravi Kumar", "12345"));
        assertThrows(BookingValidationException.class, () -> hold(104, "Ravi Kumar", "+91 5452314397"));
        assertEquals(0, repository.findInventoriesForRoomAndDates(104, List.of(IN, IN.plusDays(1))).size(),
                "no inventory rows may exist after a rejected hold");
        assertDoesNotThrow(() -> hold(104, "Ravi Kumar", "9452314397"));
    }

    // ============================================================ B2: HMAC blind index

    @Test
    @DisplayName("B2: blind index is HMAC-SHA256 - deterministic, 64 hex chars, and NOT the plain SHA-256 any attacker can recompute")
    void blindIndexIsHmacNotPlainSha256() throws Exception {
        PiiEncryptionService svc = new PiiEncryptionService(KEY, PEPPER);
        String phone = "9452314397";
        String idx = svc.blindIndex(phone);

        assertEquals(idx, svc.blindIndex(phone), "deterministic");
        assertEquals(64, idx.length());
        assertTrue(idx.matches("[0-9a-f]{64}"));
        assertFalse(idx.contains(phone));

        String plainSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(phone.getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(plainSha256, idx, "must not be the unsalted hash of the number");
        assertEquals(plainSha256, svc.legacySha256Hash(phone), "legacy hash is the old plain SHA-256 (used only to find old rows)");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(phone.getBytes(StandardCharsets.UTF_8))), idx,
                "index == HMAC-SHA256(pepper, phone)");
    }

    @Test
    @DisplayName("B2: the pepper matters - a different pepper (or, when derived, a different master key) gives a different index")
    void pepperChangesTheIndex() {
        String phone = "9452314397";
        String withPepperA = new PiiEncryptionService(KEY, PEPPER).blindIndex(phone);
        String withPepperB = new PiiEncryptionService(KEY, PEPPER + "-other").blindIndex(phone);
        assertNotEquals(withPepperA, withPepperB);

        String derived1 = new PiiEncryptionService(KEY).blindIndex(phone);
        String derived2 = new PiiEncryptionService(KEY, "").blindIndex(phone);
        String derivedOtherKey = new PiiEncryptionService("another-encryption-key-32-bytes!!").blindIndex(phone);
        assertEquals(derived1, derived2, "no pepper configured => derived from the master key, stable");
        assertNotEquals(derived1, derivedOtherKey);
        assertNotEquals(derived1, withPepperA, "an explicit pepper is independent of the master key");
    }

    @Test
    @DisplayName("B2: a too-short configured pepper is refused; hashDeterministic now delegates to the HMAC")
    void shortPepperRefused() {
        assertThrows(IllegalStateException.class, () -> new PiiEncryptionService(KEY, "short"));
        PiiEncryptionService svc = new PiiEncryptionService(KEY, PEPPER);
        assertEquals(svc.blindIndex("9452314397"), svc.hashDeterministic("9452314397"));
        assertEquals("", svc.blindIndex("  "));
        assertEquals("", svc.blindIndex(null));
    }

    @Test
    @DisplayName("B2: every legacy spelling has a distinct legacy hash, none equal to the new index")
    void legacyHashesDifferFromNewIndex() {
        PiiEncryptionService svc = new PiiEncryptionService(KEY, PEPPER);
        String newIdx = svc.blindIndex("9452314397");
        Set<String> legacy = PhoneNormalizer.legacySpellings("9452314397").stream()
                .map(svc::legacySha256Hash).collect(Collectors.toSet());
        assertEquals(4, legacy.size());
        assertFalse(legacy.contains(newIdx));
    }

    // ============================================================ B1: masked + two-factor lookup

    @Test
    @DisplayName("B1: phone-only lookup type can't carry PII or tokens (no name/phone/email/tokens/full ID/amount fields)")
    void phoneOnlySummaryHasNoSensitiveFields() {
        Set<String> fields = Arrays.stream(BookingSummaryResponse.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        assertEquals(Set.of("bookingIdHint", "status", "checkInDate", "checkOutDate", "roomLabel", "guestNameMasked"), fields);
    }

    @Test
    @DisplayName("B1: phone-only lookup returns masked summaries: partial ID, status, dates, room category, masked name")
    void phoneOnlyLookupIsMasked() {
        BookingResponse c = confirmed(101, "Ravi Kumar", "9452314397");

        List<BookingSummaryResponse> found = service.lookupSummariesByPhone("+91 94523 14397");
        assertEquals(1, found.size());
        BookingSummaryResponse s = found.get(0);
        assertEquals(c.bookingId().substring(0, 4) + "...", s.bookingIdHint());
        assertEquals("CONFIRMED", s.status().name());
        assertEquals(IN, s.checkInDate());
        assertEquals(OUT, s.checkOutDate());
        assertEquals("Deluxe Room, Double Bed", s.roomLabel());
        assertEquals("R*** K****", s.guestNameMasked());
        assertFalse(s.toString().contains(c.bookingId()), "the full booking ID never appears");
        assertFalse(s.toString().contains("Ravi"));
        assertFalse(s.toString().contains("example.com"));
    }

    @Test
    @DisplayName("B1: released holds are not listed in the phone-only lookup")
    void releasedHoldsAreHidden() {
        BookingResponse h = hold(102, "Ravi Kumar", "9452314397");
        service.releaseHold(h.bookingId(), h.bookingToken());
        assertTrue(service.lookupSummariesByPhone("9452314397").isEmpty());
    }

    @Test
    @DisplayName("B1: two-factor lookup - Booking ID + last 4 digits, or + full phone in any spelling - returns a masked summary")
    void twoFactorLookup() {
        BookingResponse c = confirmed(103, "Ravi Kumar", "9452314397");

        for (String proof : new String[]{"4397", " 4397 ", "9452314397", "+91 94523 14397", "09452314397"}) {
            VerifiedBookingSummary v = service.lookupVerified(new LookupBookingRequest(c.bookingId(), proof));
            assertEquals(c.bookingId(), v.bookingId());
            assertEquals("R*** K****", v.guestNameMasked());
            assertEquals("******4397", v.guestPhoneMasked());
            assertEquals("r***@example.com", v.guestEmailMasked());
            assertEquals(2, v.nights());
            assertEquals(c.totalAmount(), v.totalAmount());
            assertTrue(v.cancellable());
        }
        String dump = service.lookupVerified(new LookupBookingRequest(c.bookingId(), "4397")).toString();
        assertFalse(dump.contains("Ravi"));
        assertFalse(dump.contains("9452314397"));
        assertFalse(dump.contains("ravi.kumar"));

        Set<String> fields = Arrays.stream(VerifiedBookingSummary.class.getRecordComponents())
                .map(RecordComponent::getName).collect(Collectors.toSet());
        for (String forbidden : new String[]{"manageToken", "holdToken", "bookingToken", "guestName", "guestPhone", "guestEmail", "paymentReference"}) {
            assertFalse(fields.contains(forbidden), "two-factor summary must not expose " + forbidden);
        }
    }

    @Test
    @DisplayName("B1: two-factor lookup fails identically for a wrong last-4, a wrong phone and an unknown ID (no probing)")
    void twoFactorLookupDoesNotLeak() {
        BookingResponse c = confirmed(104, "Ravi Kumar", "9452314397");

        String m1 = assertThrows(BookingNotFoundException.class,
                () -> service.lookupVerified(new LookupBookingRequest(c.bookingId(), "0000"))).getMessage();
        String m2 = assertThrows(BookingNotFoundException.class,
                () -> service.lookupVerified(new LookupBookingRequest(c.bookingId(), "9000000000"))).getMessage();
        String m3 = assertThrows(BookingNotFoundException.class,
                () -> service.lookupVerified(new LookupBookingRequest("does-not-exist-123", "4397"))).getMessage();
        String m4 = assertThrows(BookingNotFoundException.class,
                () -> service.lookupVerified(new LookupBookingRequest("../../etc", "4397"))).getMessage();
        assertEquals(m1, m2);
        assertEquals(m1, m3);
        assertEquals(m1, m4);
        assertFalse(m1.contains(c.bookingId()));

        // Malformed proof is a 400, never a lookup.
        assertThrows(BookingValidationException.class, () -> service.lookupVerified(new LookupBookingRequest(c.bookingId(), "123")));
        assertThrows(BookingValidationException.class, () -> service.lookupVerified(new LookupBookingRequest(c.bookingId(), "12345")));
        assertThrows(BookingValidationException.class, () -> service.lookupVerified(new LookupBookingRequest(c.bookingId(), "abcd12")));
    }

    @Test
    @DisplayName("B1: the manage token is only obtainable with Booking ID + FULL phone (access), never with last-4 and never from a phone-only lookup")
    void accessNeedsTheFullPhone() {
        BookingResponse c = confirmed(105, "Ravi Kumar", "9452314397");

        BookingResponse opened = service.accessBooking(c.bookingId(), new AccessBookingRequest("+91 94523 14397"));
        assertNotNull(opened.manageToken());
        assertEquals("Ravi Kumar", opened.guestName(), "authenticated access returns the real details");

        // Wrong-but-valid number: 403. Malformed / last-4 only: 400 before touching the booking.
        assertThrows(BookingForbiddenException.class, () -> service.accessBooking(c.bookingId(), new AccessBookingRequest("9000000000")));
        assertThrows(BookingValidationException.class, () -> service.accessBooking(c.bookingId(), new AccessBookingRequest("4397")));
        assertThrows(BookingValidationException.class, () -> service.accessBooking(c.bookingId(), new AccessBookingRequest("not a phone")));
        assertThrows(BookingNotFoundException.class, () -> service.accessBooking("nope-nope-nope", new AccessBookingRequest("9452314397")));
    }

    @Test
    @DisplayName("B1: GET by booking ID no longer hands out hold tokens or unmasked PII")
    void getBookingIsRedacted() {
        BookingResponse h = hold(106, "Ravi Kumar", "9452314397");
        assertNotNull(h.bookingToken(), "the creator still gets the hold token from the hold response");

        BookingResponse viewed = service.getBooking(h.bookingId());
        assertNull(viewed.bookingToken(), "a live hold token must not be readable by booking ID alone");
        assertNull(viewed.manageToken());
        assertEquals("R*** K****", viewed.guestName());
        assertEquals("******4397", viewed.guestPhone());
        assertEquals("r***@example.com", viewed.guestEmail());

        BookingResponse paid = confirmedUpi(107, "Ravi Kumar", "9452314398", "abcd12345678");
        BookingResponse viewedPaid = service.getBooking(paid.bookingId());
        assertNull(viewedPaid.paymentReference(), "the UTR is not shown without authentication");
        assertNull(viewedPaid.manageToken());
    }

    private BookingResponse confirmedUpi(int room, String name, String phone, String utr) {
        BookingResponse h = hold(room, name, phone);
        return service.confirmBooking(h.bookingId(), ConfirmBookingRequest.upi(utr, h.bookingToken()));
    }

    @Test
    @DisplayName("B6: a UTR is 12 letters/digits, stored upper-case, and case variants cannot be used twice")
    void utrIsTwelveAlphanumericAndCaseInsensitive() {
        BookingResponse a = confirmedUpi(108, "Ravi Kumar", "9452314397", "abcd12345678");
        assertEquals("ABCD12345678", a.paymentReference());

        BookingResponse other = hold(109, "Ravi Kumar", "9452314398");
        assertThrows(BookingValidationException.class, () -> service.confirmBooking(other.bookingId(),
                ConfirmBookingRequest.upi("ABCD12345678", other.bookingToken())), "same UTR in another case is a duplicate");
        for (String bad : new String[]{"ABCD1234567", "ABCD123456789", "ABCD-2345678", "ABCD 2345678", ""}) {
            assertThrows(BookingValidationException.class, () -> service.confirmBooking(other.bookingId(),
                    ConfirmBookingRequest.upi(bad, other.bookingToken())), "'" + bad + "'");
        }
    }

    // ============================================================ B6: sanitising

    @Test
    @DisplayName("B6: markup in the guest name is stripped before storage")
    void scriptTagsAreStrippedFromNames() {
        assertEquals("Ravi", InputSanitizer.clean("<script>alert(1)</script>Ravi"));
        assertEquals("Ravi Kumar", InputSanitizer.clean("<b>Ravi</b>   Kumar"));
        assertEquals("", InputSanitizer.clean("<script>alert(1)</script>"));
        assertEquals("Ravi", InputSanitizer.clean("<SCRIPT src=x>evil()</SCRIPT> Ravi"));
        assertEquals("Ravi", InputSanitizer.clean("Ravi<img src=x onerror=alert(1)"));
        assertEquals("Ravi Kumar", InputSanitizer.clean("Ravi\u0000\n Kumar"));
        assertNull(InputSanitizer.clean(null));

        BookingResponse h = hold(110, "<script>alert(1)</script>Ravi <i>Kumar</i>", "9452314397");
        assertEquals("Ravi Kumar", repository.findBookingById(h.bookingId()).orElseThrow().guestName());
    }

    @Test
    @DisplayName("B6: masking helpers")
    void maskingHelpers() {
        assertEquals("R*** K****", InputSanitizer.maskName("Ravi Kumar"));
        assertEquals("A*", InputSanitizer.maskName("Al"));
        assertEquals("A", InputSanitizer.maskName("A"));
        assertEquals("Guest", InputSanitizer.maskName("  "));
        assertEquals("r***@example.com", InputSanitizer.maskEmail("ravi.kumar@example.com"));
        assertNull(InputSanitizer.maskEmail(null));
    }

    // ============================================================ B7: CORS origin list

    @Test
    @DisplayName("B7: CORS origins are exact, trimmed, never a wildcard; empty falls back to local dev only")
    void corsOriginParsing() {
        assertEquals(List.of("https://d111.cloudfront.net", "http://localhost:5173"),
                CorsOrigins.parse(" https://d111.cloudfront.net/ , http://localhost:5173 "));
        List<String> localDev = List.of("http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000");
        assertEquals(localDev, CorsOrigins.parse(""));
        assertEquals(localDev, CorsOrigins.parse(null));
        assertFalse(CorsOrigins.parse("").contains("*"));
        assertThrows(IllegalStateException.class, () -> CorsOrigins.parse("*"));
        assertThrows(IllegalStateException.class, () -> CorsOrigins.parse("https://a.example,*"));
    }
}
