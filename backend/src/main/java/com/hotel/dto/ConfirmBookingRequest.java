package com.hotel.dto;

import com.hotel.model.PaymentMethod;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Confirms a HOLD.
 * <ul>
 *   <li>PAY_AT_HOTEL: no reference needed; the guest pays at the front desk on arrival. This is the
 *       only option the public website offers as of the "Pay at Hotel Only" simplification, and is
 *       the default when {@code paymentMethod} is omitted (an older client, or a bare {} body).</li>
 *   <li>UPI_QR: {@code paymentReference} is the 12-digit UPI transaction reference (UTR) from the
 *       guest's payment app. No longer reachable from the guest website UI, but still accepted here
 *       (e.g. for a front-desk-assisted booking) so existing integrations are not broken.</li>
 * </ul>
 * The signed hold token may be sent here or in the X-Booking-Token header.
 */
public record ConfirmBookingRequest(
        PaymentMethod paymentMethod,

        @Pattern(regexp = "^([A-Za-z0-9]{12})?$", message = "UTR must be exactly 12 letters or digits")
        String paymentReference,

        @Size(max = 4096, message = "bookingToken is too long")
        String bookingToken
) {
    public ConfirmBookingRequest {
        paymentReference = paymentReference == null ? null : paymentReference.trim();
        if (paymentMethod == null) {
            paymentMethod = PaymentMethod.PAY_AT_HOTEL;
        }
    }

    public static ConfirmBookingRequest payAtHotel(String bookingToken) {
        return new ConfirmBookingRequest(PaymentMethod.PAY_AT_HOTEL, null, bookingToken);
    }

    public static ConfirmBookingRequest upi(String utr, String bookingToken) {
        return new ConfirmBookingRequest(PaymentMethod.UPI_QR, utr, bookingToken);
    }
}
