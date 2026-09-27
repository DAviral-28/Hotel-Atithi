package com.hotel.model;

/** How the guest chose to pay for a booking. Both options are zero-fee (no payment gateway). */
public enum PaymentMethod {
    /** Guest pays cash / UPI / card at the front desk on arrival. */
    PAY_AT_HOTEL,
    /** Guest paid straight to the hotel's UPI ID (QR / intent) and supplied the 12-digit UTR. */
    UPI_QR
}
