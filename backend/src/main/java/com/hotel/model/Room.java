package com.hotel.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Immutable room entity representing one of the 32 hotel rooms.
 */
public record Room(
        int roomNumber,
        int floor,
        RoomType type,
        BedType bedType,
        BigDecimal baseRate,
        BigDecimal extraPersonRate,
        BigDecimal taxRatePercent,
        int baseOccupancy,
        int maxOccupancy
) {
    public static final String STANDARD_CHECKOUT_TIME = "12:00 PM";
    public static final BigDecimal DEFAULT_GST_PERCENT = new BigDecimal("5.00");

    /**
     * Calculate total price including extra person charge and 5% GST.
     *
     * @param numberOfGuests number of staying guests
     * @param numberOfNights number of nights
     * @return total cost breakdown
     */
    public TariffCalculation calculateTariff(int numberOfGuests, int numberOfNights) {
        if (numberOfGuests < 1 || numberOfGuests > maxOccupancy) {
            throw new IllegalArgumentException("Guests must be between 1 and " + maxOccupancy + " for Room " + roomNumber);
        }
        if (numberOfNights < 1) {
            throw new IllegalArgumentException("Nights must be at least 1");
        }

        int extraGuests = Math.max(0, numberOfGuests - baseOccupancy);
        BigDecimal extraGuestChargePerNight = extraPersonRate.multiply(BigDecimal.valueOf(extraGuests));
        BigDecimal roomRatePerNight = baseRate.add(extraGuestChargePerNight);
        BigDecimal subtotal = roomRatePerNight.multiply(BigDecimal.valueOf(numberOfNights));

        BigDecimal gstRate = taxRatePercent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal gstAmount = subtotal.multiply(gstRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount = subtotal.add(gstAmount).setScale(2, RoundingMode.HALF_UP);

        return new TariffCalculation(
                roomNumber,
                numberOfGuests,
                numberOfNights,
                baseRate,
                extraGuests,
                extraGuestChargePerNight,
                subtotal,
                taxRatePercent,
                gstAmount,
                totalAmount,
                STANDARD_CHECKOUT_TIME
        );
    }

    public record TariffCalculation(
            int roomNumber,
            int numberOfGuests,
            int numberOfNights,
            BigDecimal baseRatePerNight,
            int extraGuestsCount,
            BigDecimal extraGuestChargePerNight,
            BigDecimal subtotal,
            BigDecimal gstPercentage,
            BigDecimal gstAmount,
            BigDecimal totalAmount,
            String standardCheckoutTime
    ) {}
}
