package com.hotel.repository;

import com.hotel.exception.BookingConflictException;
import com.hotel.exception.BookingValidationException;
import com.hotel.model.Booking;
import com.hotel.model.BookingStatus;
import com.hotel.model.InventoryStatus;
import com.hotel.model.PaymentMethod;
import com.hotel.model.PaymentStatus;
import com.hotel.model.RoomInventory;
import com.hotel.util.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Repository
@Profile("!local")
public class DynamoDbBookingRepository implements BookingRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbBookingRepository.class);

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final com.hotel.security.PiiEncryptionService piiEncryptionService;
    /**
     * Rows written before the HMAC blind index carry GSI1PK = SHA-256(phone). While true, lookups also
     * query those legacy keys and re-key the rows they find (lazy migration). Set
     * hotel.security.legacy-index-fallback=false once no legacy rows remain.
     */
    private final boolean legacyIndexFallback;

    public DynamoDbBookingRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${hotel.aws.table-name:HotelBookings}") String tableName,
            com.hotel.security.PiiEncryptionService piiEncryptionService,
            @Value("${hotel.security.legacy-index-fallback:true}") boolean legacyIndexFallback
    ) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.piiEncryptionService = piiEncryptionService;
        this.legacyIndexFallback = legacyIndexFallback;
    }

    @Override
    public void holdRoom(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();

        // 1. Put Booking record with HOLD status
        actions.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(mapBookingToItem(booking))
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build());

        // 2. Lock each date in the range atomically
        for (RoomInventory inv : inventories) {
            Map<String, String> expNames = Map.of(
                    "#status", "status",
                    "#lockExpiresAt", "lockExpiresAt"
            );
            Map<String, AttributeValue> expValues = Map.of(
                    ":available", AttributeValue.builder().s(InventoryStatus.AVAILABLE.name()).build(),
                    ":hold", AttributeValue.builder().s(InventoryStatus.HOLD.name()).build(),
                    ":now", AttributeValue.builder().n(String.valueOf(nowEpochSeconds)).build()
            );

            actions.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(mapInventoryToItem(inv))
                            .conditionExpression("attribute_not_exists(PK) OR #status = :available OR (#status = :hold AND #lockExpiresAt < :now)")
                            .expressionAttributeNames(expNames)
                            .expressionAttributeValues(expValues)
                            .build())
                    .build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actions)
                    .build());
            log.info("Successfully acquired hold for room {} on booking {}", booking.roomNumber(), booking.bookingId());
        } catch (TransactionCanceledException ex) {
            log.warn("Hold transaction canceled for room {}: {}", booking.roomNumber(), ex.getMessage());
            List<LocalDate> conflictingDates = findConflictingDates(
                    booking.roomNumber(),
                    inventories.stream().map(RoomInventory::date).toList(),
                    nowEpochSeconds
            );
            throw new BookingConflictException(
                    "Room " + booking.roomNumber() + " is already reserved or held for requested dates",
                    conflictingDates
            );
        }
    }

    @Override
    public void confirmBooking(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();

        // 1. Update Booking status to CONFIRMED and store the payment outcome. The expression is built
        //    dynamically because DynamoDB rejects a null AttributeValue (no UTR for pay-at-hotel).
        Map<String, String> names = new HashMap<>();
        names.put("#status", "status");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build());
        values.put(":hold", AttributeValue.builder().s(BookingStatus.HOLD.name()).build());
        values.put(":now", AttributeValue.builder().n(String.valueOf(nowEpochSeconds)).build());
        values.put(":confAt", AttributeValue.builder().n(String.valueOf(booking.confirmedAt())).build());
        values.put(":payStatus", AttributeValue.builder().s(booking.paymentStatus().name()).build());
        values.put(":paid", AttributeValue.builder().n(booking.amountPaid().toPlainString()).build());
        StringBuilder set = new StringBuilder("SET #status = :confirmed, confirmedAt = :confAt, paymentStatus = :payStatus, amountPaid = :paid");
        if (booking.paymentMethod() != null) {
            set.append(", paymentMethod = :payMethod");
            values.put(":payMethod", AttributeValue.builder().s(booking.paymentMethod().name()).build());
        }
        if (booking.paymentReference() != null) {
            set.append(", paymentReference = :payRef");
            values.put(":payRef", AttributeValue.builder().s(booking.paymentReference()).build());
        }
        if (booking.paidAt() != null) {
            set.append(", paidAt = :paidAt");
            values.put(":paidAt", AttributeValue.builder().n(String.valueOf(booking.paidAt())).build());
        }

        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(Booking.buildPk(booking.bookingId())).build(),
                                "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                        ))
                        .updateExpression(set.toString())
                        .conditionExpression("#status = :hold AND expiresAt > :now")
                        .expressionAttributeNames(names)
                        .expressionAttributeValues(values)
                        .build())
                .build());

        // 2. Update all inventory rows to CONFIRMED
        for (RoomInventory inv : inventories) {
            actions.add(TransactWriteItem.builder()
                    .update(Update.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    "PK", AttributeValue.builder().s(RoomInventory.buildPk(inv.roomNumber())).build(),
                                    "SK", AttributeValue.builder().s(RoomInventory.buildSk(inv.date())).build()
                            ))
                            .updateExpression("SET #status = :confirmed, bookingId = :bId, lockExpiresAt = :zero")
                            .conditionExpression("bookingId = :bId")
                            .expressionAttributeNames(Map.of("#status", "status"))
                            .expressionAttributeValues(Map.of(
                                    ":confirmed", AttributeValue.builder().s(InventoryStatus.CONFIRMED.name()).build(),
                                    ":bId", AttributeValue.builder().s(booking.bookingId()).build(),
                                    ":zero", AttributeValue.builder().n("0").build()
                            ))
                            .build())
                    .build());
        }

        // 3. Claim the UTR (UPI only) so it can never be reused for another booking.
        int claimIndex = -1;
        if (booking.paymentMethod() == PaymentMethod.UPI_QR && booking.paymentReference() != null) {
            claimIndex = actions.size();
            actions.add(buildUtrClaimAction(booking.paymentReference(), booking.bookingId(), booking.ttl()));
            // Queue for staff: an online payment is self-declared until someone checks it against the UPI statement.
            actions.add(buildPendingReconciliationPut(booking.bookingId(), booking.ttl()));
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actions)
                    .build());
            log.info("Successfully confirmed booking {} ({})", booking.bookingId(), booking.paymentMethod());
        } catch (TransactionCanceledException ex) {
            log.warn("Confirm transaction failed for booking {}: {}", booking.bookingId(), ex.getMessage());
            if (claimFailed(ex, claimIndex)) {
                throw new BookingValidationException(UTR_REUSED_MESSAGE);
            }
            throw new BookingValidationException("Booking " + booking.bookingId() + " could not be confirmed (hold may have expired).");
        }
    }

    static final String UTR_REUSED_MESSAGE =
            "This UTR / transaction reference has already been used for another booking. Please check the number and try again.";

    /** Put of the one-per-UTR claim item; fails if the UTR was ever used before. */
    private TransactWriteItem buildUtrClaimAction(String utr, String bookingId, long ttl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s("UTR#" + utr).build());
        item.put("SK", AttributeValue.builder().s("CLAIM").build());
        item.put("bookingId", AttributeValue.builder().s(bookingId).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(item)
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build();
    }

    static final String RECON_PK = "RECON#PENDING";

    /** Marker row "this booking has an unreconciled UPI payment". Queried by PK (no scan, no new index). */
    private TransactWriteItem buildPendingReconciliationPut(String bookingId, long ttl) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(RECON_PK).build());
        item.put("SK", AttributeValue.builder().s("BOOKING#" + bookingId).build());
        item.put("bookingId", AttributeValue.builder().s(bookingId).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(ttl)).build());
        return TransactWriteItem.builder()
                .put(Put.builder().tableName(tableName).item(item).build())
                .build();
    }

    private TransactWriteItem buildPendingReconciliationDelete(String bookingId) {
        return TransactWriteItem.builder()
                .delete(Delete.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(RECON_PK).build(),
                                "SK", AttributeValue.builder().s("BOOKING#" + bookingId).build()))
                        .build())
                .build();
    }

    /** True when the cancellation reason for the action at {@code index} is a failed condition. */
    private static boolean claimFailed(TransactionCanceledException ex, int index) {
        if (index < 0 || !ex.hasCancellationReasons() || ex.cancellationReasons().size() <= index) {
            return false;
        }
        return "ConditionalCheckFailed".equals(ex.cancellationReasons().get(index).code());
    }

    @Override
    public void recordPayment(Booking updated, BigDecimal expectedPreviousAmountPaid, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(Booking.buildPk(updated.bookingId())).build(),
                                "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                        ))
                        .updateExpression("SET amountPaid = :paid, paymentStatus = :payStatus, paymentMethod = :payMethod, "
                                + "paymentReference = :payRef, paidAt = :paidAt")
                        .conditionExpression("(#status = :confirmed OR #status = :checkedIn) AND (amountPaid = :prev OR attribute_not_exists(amountPaid))")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(
                                ":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build(),
                                ":checkedIn", AttributeValue.builder().s(BookingStatus.CHECKED_IN.name()).build(),
                                ":prev", AttributeValue.builder().n(expectedPreviousAmountPaid.toPlainString()).build(),
                                ":paid", AttributeValue.builder().n(updated.amountPaid().toPlainString()).build(),
                                ":payStatus", AttributeValue.builder().s(updated.paymentStatus().name()).build(),
                                ":payMethod", AttributeValue.builder().s(updated.paymentMethod().name()).build(),
                                ":payRef", AttributeValue.builder().s(updated.paymentReference()).build(),
                                ":paidAt", AttributeValue.builder().n(String.valueOf(updated.paidAt())).build()
                        ))
                        .build())
                .build());
        // The UTR of THIS payment is the last comma-separated entry of paymentReference.
        String utr = updated.paymentReference().substring(updated.paymentReference().lastIndexOf(',') + 1);
        actions.add(buildUtrClaimAction(utr, updated.bookingId(), updated.ttl()));
        actions.add(buildPendingReconciliationPut(updated.bookingId(), updated.ttl()));

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Recorded UPI payment for booking {}", updated.bookingId());
        } catch (TransactionCanceledException ex) {
            log.warn("Payment transaction failed for booking {}: {}", updated.bookingId(), ex.getMessage());
            if (claimFailed(ex, 1)) {
                throw new BookingValidationException(UTR_REUSED_MESSAGE);
            }
            throw new BookingValidationException("Booking " + updated.bookingId()
                    + " changed while the payment was being recorded. Please reload and try again.");
        }
    }

    @Override
    public void cancelBooking(Booking cancelled, List<RoomInventory> inventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();

        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":cancelled", AttributeValue.builder().s(BookingStatus.CANCELLED.name()).build());
        values.put(":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build());
        values.put(":cancelledAt", AttributeValue.builder().n(String.valueOf(cancelled.cancelledAt())).build());
        values.put(":late", AttributeValue.builder().bool(cancelled.lateCancellation()).build());
        values.put(":ttl", AttributeValue.builder().n(String.valueOf(cancelled.ttl())).build());
        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(Booking.buildPk(cancelled.bookingId())).build(),
                                "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                        ))
                        .updateExpression("SET #status = :cancelled, cancelledAt = :cancelledAt, lateCancellation = :late, #ttl = :ttl")
                        .conditionExpression("#status = :confirmed")
                        .expressionAttributeNames(Map.of("#status", "status", "#ttl", "ttl"))
                        .expressionAttributeValues(values)
                        .build())
                .build());

        // Delete the inventory rows (this also drops them from GSI2, so availability search sees the
        // room as free at once). The condition guarantees we never delete another guest's row.
        for (RoomInventory inv : inventories) {
            actions.add(TransactWriteItem.builder()
                    .delete(Delete.builder()
                            .tableName(tableName)
                            .key(Map.of(
                                    "PK", AttributeValue.builder().s(RoomInventory.buildPk(inv.roomNumber())).build(),
                                    "SK", AttributeValue.builder().s(RoomInventory.buildSk(inv.date())).build()
                            ))
                            .conditionExpression("bookingId = :bId")
                            .expressionAttributeValues(Map.of(
                                    ":bId", AttributeValue.builder().s(cancelled.bookingId()).build()
                            ))
                            .build())
                    .build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Cancelled booking {} (room {}, late={})", cancelled.bookingId(), cancelled.roomNumber(),
                    cancelled.lateCancellation());
        } catch (TransactionCanceledException ex) {
            log.warn("Cancel transaction failed for booking {}: {}", cancelled.bookingId(), ex.getMessage());
            throw new BookingValidationException("Booking " + cancelled.bookingId()
                    + " could not be cancelled (its status changed). Please reload and try again.");
        }
    }

    @Override
    public void extendBooking(Booking updatedBooking, List<RoomInventory> extensionInventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();

        // 1. Update Booking checkout date and amounts
        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(Booking.buildPk(updatedBooking.bookingId())).build(),
                                "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                        ))
                        .updateExpression("SET checkOutDate = :newOut, subtotal = :newSub, gstAmount = :newGst, totalAmount = :newTot, #ttl = :newTtl, paymentStatus = :newPayStatus")
                        .conditionExpression("bookingId = :bId AND (#status = :confirmed OR #status = :checkedIn)")
                        // "ttl" is a DynamoDB reserved word, so it must be aliased.
                        .expressionAttributeNames(Map.of("#status", "status", "#ttl", "ttl"))
                        .expressionAttributeValues(Map.of(
                                ":bId", AttributeValue.builder().s(updatedBooking.bookingId()).build(),
                                ":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build(),
                                ":checkedIn", AttributeValue.builder().s(BookingStatus.CHECKED_IN.name()).build(),
                                ":newOut", AttributeValue.builder().s(updatedBooking.checkOutDate().toString()).build(),
                                ":newSub", AttributeValue.builder().n(updatedBooking.subtotal().toPlainString()).build(),
                                ":newGst", AttributeValue.builder().n(updatedBooking.gstAmount().toPlainString()).build(),
                                ":newTot", AttributeValue.builder().n(updatedBooking.totalAmount().toPlainString()).build(),
                                ":newTtl", AttributeValue.builder().n(String.valueOf(updatedBooking.ttl())).build(),
                                ":newPayStatus", AttributeValue.builder().s(updatedBooking.paymentStatus().name()).build()
                        ))
                        .build())
                .build());

        // 2. Lock extension dates directly as CONFIRMED
        for (RoomInventory inv : extensionInventories) {
            Map<String, String> expNames = Map.of(
                    "#status", "status",
                    "#lockExpiresAt", "lockExpiresAt"
            );
            Map<String, AttributeValue> expValues = Map.of(
                    ":available", AttributeValue.builder().s(InventoryStatus.AVAILABLE.name()).build(),
                    ":hold", AttributeValue.builder().s(InventoryStatus.HOLD.name()).build(),
                    ":now", AttributeValue.builder().n(String.valueOf(nowEpochSeconds)).build()
            );

            actions.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(mapInventoryToItem(inv))
                            .conditionExpression("attribute_not_exists(PK) OR #status = :available OR (#status = :hold AND #lockExpiresAt < :now)")
                            .expressionAttributeNames(expNames)
                            .expressionAttributeValues(expValues)
                            .build())
                    .build());
        }

        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(actions)
                    .build());
            log.info("Successfully extended booking {} to {}", updatedBooking.bookingId(), updatedBooking.checkOutDate());
        } catch (TransactionCanceledException ex) {
            log.warn("Extension failed for booking {}: {}", updatedBooking.bookingId(), ex.getMessage());
            List<LocalDate> conflictingDates = findConflictingDates(
                    updatedBooking.roomNumber(),
                    extensionInventories.stream().map(RoomInventory::date).toList(),
                    nowEpochSeconds
            );
            throw new BookingConflictException("Room unavailable for requested extension period", conflictingDates);
        }
    }

    @Override
    public void releaseHold(Booking released, List<RoomInventory> inventories, long nowEpochSeconds) {
        boolean holdAlreadyExpired = nowEpochSeconds >= released.expiresAt();

        if (!holdAlreadyExpired) {
            List<TransactWriteItem> actions = new ArrayList<>();
            actions.add(buildReleaseBookingAction(released));

            // Delete (not "AVAILABLE"-flag) the lock rows: a missing row already satisfies the hold
            // condition (attribute_not_exists(PK)), and deleting also drops them from GSI2.
            for (RoomInventory inv : inventories) {
                actions.add(TransactWriteItem.builder()
                        .delete(Delete.builder()
                                .tableName(tableName)
                                .key(Map.of(
                                        "PK", AttributeValue.builder().s(RoomInventory.buildPk(inv.roomNumber())).build(),
                                        "SK", AttributeValue.builder().s(RoomInventory.buildSk(inv.date())).build()
                                ))
                                .conditionExpression("bookingId = :bId AND #status = :hold")
                                .expressionAttributeNames(Map.of("#status", "status"))
                                .expressionAttributeValues(Map.of(
                                        ":bId", AttributeValue.builder().s(released.bookingId()).build(),
                                        ":hold", AttributeValue.builder().s(InventoryStatus.HOLD.name()).build()
                                ))
                                .build())
                        .build());
            }

            try {
                dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                        .transactItems(actions)
                        .build());
                log.info("Released hold for booking {} (room {})", released.bookingId(), released.roomNumber());
                return;
            } catch (TransactionCanceledException ex) {
                // Most likely the hold expired between our read and this write and another guest
                // took the rows, or a row was already TTL-deleted. Fall through to the booking-only path.
                log.warn("Full hold release canceled for booking {}: {}", released.bookingId(), ex.getMessage());
            }
        }

        // The hold is expired (or the race above happened): the inventory rows are either free or
        // belong to someone else, so only tidy this booking's own record.
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(buildReleaseBookingAction(released))
                    .build());
            log.info("Marked expired hold {} as released (inventory untouched)", released.bookingId());
        } catch (TransactionCanceledException ex) {
            log.warn("Release failed for booking {}: {}", released.bookingId(), ex.getMessage());
            throw new BookingValidationException(
                    "Booking " + released.bookingId() + " can no longer be released (its status has changed).");
        }
    }

    private TransactWriteItem buildReleaseBookingAction(Booking released) {
        return TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(Map.of(
                                "PK", AttributeValue.builder().s(Booking.buildPk(released.bookingId())).build(),
                                "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                        ))
                        .updateExpression("SET #status = :released, #ttl = :ttl")
                        .conditionExpression("#status = :hold")
                        .expressionAttributeNames(Map.of("#status", "status", "#ttl", "ttl"))
                        .expressionAttributeValues(Map.of(
                                ":released", AttributeValue.builder().s(BookingStatus.RELEASED.name()).build(),
                                ":hold", AttributeValue.builder().s(BookingStatus.HOLD.name()).build(),
                                ":ttl", AttributeValue.builder().n(String.valueOf(released.ttl())).build()
                        ))
                        .build())
                .build();
    }

    @Override
    public Optional<Booking> findBookingById(String bookingId) {
        GetItemResponse response = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.builder().s(Booking.buildPk(bookingId)).build(),
                        "SK", AttributeValue.builder().s(Booking.buildSk()).build()
                ))
                .build());

        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(mapItemToBooking(response.item()));
    }

    @Override
    public List<Booking> findBookingsByGuestPhone(String guestPhone) {
        if (guestPhone == null || guestPhone.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // Everything is keyed on the canonical 10-digit number, however the caller spelled it.
        String canonical = PhoneNormalizer.forStored(guestPhone);
        if (canonical.isEmpty()) {
            return Collections.emptyList();
        }

        String hmacKey = guestKey(piiEncryptionService.blindIndex(canonical));
        Map<String, Map<String, AttributeValue>> byBookingId = new LinkedHashMap<>();
        for (Map<String, AttributeValue> item : queryGsi1(hmacKey)) {
            byBookingId.put(item.get("bookingId").s(), item);
        }

        if (legacyIndexFallback) {
            // Older rows: GSI1PK = SHA-256 of whatever spelling the phone was stored with.
            for (String candidate : PhoneNormalizer.legacySpellings(canonical)) {
                String legacyKey = guestKey(piiEncryptionService.legacySha256Hash(candidate));
                for (Map<String, AttributeValue> item : queryGsi1(legacyKey)) {
                    if (byBookingId.putIfAbsent(item.get("bookingId").s(), item) == null) {
                        migrateIndexKey(item, legacyKey, hmacKey);
                    }
                }
            }
        }

        return byBookingId.values().stream()
                .map(this::mapItemToBooking)
                .sorted(Comparator.comparing(Booking::checkInDate).reversed())
                .toList();
    }

    private static String guestKey(String hash) {
        return "GUEST#" + hash;
    }

    // GSI1 has projectionType ALL (see hotel-infra-stack.ts), so every booking attribute is returned
    // directly from the index - no follow-up GetItem per result is needed.
    private List<Map<String, AttributeValue>> queryGsi1(String gsi1Pk) {
        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName("GSI1")
                .keyConditionExpression("GSI1PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(gsi1Pk).build()
                ))
                .scanIndexForward(false) // GSI1SK = BOOKING#<checkInDate>: most recent stay first
                .build());
        return response.items();
    }

    /** Best-effort: re-key a legacy row to the HMAC index. Failure only means it is migrated on a later lookup. */
    private void migrateIndexKey(Map<String, AttributeValue> item, String oldKey, String newKey) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", item.get("PK"),
                            "SK", item.get("SK")))
                    .updateExpression("SET GSI1PK = :new")
                    .conditionExpression("GSI1PK = :old")
                    .expressionAttributeValues(Map.of(
                            ":new", AttributeValue.builder().s(newKey).build(),
                            ":old", AttributeValue.builder().s(oldKey).build()))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // someone else already migrated it
        } catch (DynamoDbException e) {
            log.warn("Could not migrate a legacy guest index key: {}", e.getMessage());
        }
    }

    @Override
    public List<RoomInventory> findInventoriesForRoomAndDates(int roomNumber, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return Collections.emptyList();
        }

        LocalDate minDate = Collections.min(dates);
        LocalDate maxDate = Collections.max(dates);

        QueryResponse response = dynamoDbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk AND SK BETWEEN :startSk AND :endSk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.builder().s(RoomInventory.buildPk(roomNumber)).build(),
                        ":startSk", AttributeValue.builder().s(RoomInventory.buildSk(minDate)).build(),
                        ":endSk", AttributeValue.builder().s(RoomInventory.buildSk(maxDate)).build()
                ))
                .build());

        Set<LocalDate> dateSet = new HashSet<>(dates);
        return response.items().stream()
                .map(this::mapItemToInventory)
                .filter(inv -> dateSet.contains(inv.date()))
                .collect(Collectors.toList());
    }

    @Override
    public List<RoomInventory> findInventoriesForDates(List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return Collections.emptyList();
        }

        // GSI2 partitions inventory by calendar date, so one Query returns every room's lock state
        // for that night. A 1-night search costs 1 Query (~0.5 RCU) instead of 32 per-room Queries,
        // which matters on a 5-RCU free-tier table where the old approach throttled after a few visitors.
        List<RoomInventory> allInventories = new ArrayList<>();
        for (LocalDate date : new LinkedHashSet<>(dates)) {
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest.Builder request = QueryRequest.builder()
                        .tableName(tableName)
                        .indexName("GSI2")
                        .keyConditionExpression("GSI2PK = :pk")
                        .expressionAttributeValues(Map.of(
                                ":pk", AttributeValue.builder().s(RoomInventory.buildGsi2Pk(date)).build()
                        ));
                if (lastKey != null) {
                    request.exclusiveStartKey(lastKey);
                }
                QueryResponse response = dynamoDbClient.query(request.build());
                response.items().stream().map(this::mapItemToInventory).forEach(allInventories::add);
                lastKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
            } while (lastKey != null);
        }
        return allInventories;
    }

    // ------------------------------------------------------------------------------------------
    // Phase 4 - front-desk (staff) operations
    // ------------------------------------------------------------------------------------------

    @Override
    public List<Booking> findBookingsByIds(Collection<String> bookingIds) {
        List<String> ids = bookingIds.stream().distinct().toList();
        List<Booking> result = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 100) {       // BatchGetItem accepts at most 100 keys
            List<Map<String, AttributeValue>> keys = new ArrayList<>();
            for (String id : ids.subList(from, Math.min(from + 100, ids.size()))) {
                keys.add(Map.of(
                        "PK", AttributeValue.builder().s(Booking.buildPk(id)).build(),
                        "SK", AttributeValue.builder().s(Booking.buildSk()).build()));
            }
            Map<String, KeysAndAttributes> request = Map.of(tableName, KeysAndAttributes.builder().keys(keys).build());
            int attempts = 0;
            while (!request.isEmpty() && attempts++ < 5) {
                BatchGetItemResponse response = dynamoDbClient.batchGetItem(
                        BatchGetItemRequest.builder().requestItems(request).build());
                for (Map<String, AttributeValue> item : response.responses().getOrDefault(tableName, List.of())) {
                    result.add(mapItemToBooking(item));
                }
                request = response.hasUnprocessedKeys() ? response.unprocessedKeys() : Map.of();
            }
        }
        return result;
    }

    @Override
    public List<String> findPendingReconciliationIds() {
        List<String> ids = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(tableName)
                    .keyConditionExpression("PK = :pk")
                    .expressionAttributeValues(Map.of(":pk", AttributeValue.builder().s(RECON_PK).build()));
            if (lastKey != null) {
                request.exclusiveStartKey(lastKey);
            }
            QueryResponse response = dynamoDbClient.query(request.build());
            response.items().forEach(item -> ids.add(item.get("bookingId").s()));
            lastKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (lastKey != null);
        return ids;
    }

    @Override
    public void checkIn(Booking updated) {
        try {
            dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(bookingKey(updated.bookingId()))
                    .updateExpression("SET #status = :checkedIn, checkedInAt = :at")
                    .conditionExpression("#status = :confirmed")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":checkedIn", AttributeValue.builder().s(BookingStatus.CHECKED_IN.name()).build(),
                            ":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build(),
                            ":at", AttributeValue.builder().n(String.valueOf(updated.checkedInAt())).build()))
                    .build());
            log.info("Checked in booking {} (room {})", updated.bookingId(), updated.roomNumber());
        } catch (ConditionalCheckFailedException ex) {
            throw new BookingValidationException("Booking " + updated.bookingId()
                    + " can no longer be checked in (its status changed). Please refresh.");
        }
    }

    @Override
    public void checkOut(Booking updated, List<RoomInventory> releasedInventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(bookingKey(updated.bookingId()))
                        .updateExpression("SET #status = :out, checkedOutAt = :at, checkOutDate = :outDate, #ttl = :ttl")
                        .conditionExpression("#status = :checkedIn")
                        .expressionAttributeNames(Map.of("#status", "status", "#ttl", "ttl"))
                        .expressionAttributeValues(Map.of(
                                ":out", AttributeValue.builder().s(BookingStatus.CHECKED_OUT.name()).build(),
                                ":checkedIn", AttributeValue.builder().s(BookingStatus.CHECKED_IN.name()).build(),
                                ":at", AttributeValue.builder().n(String.valueOf(updated.checkedOutAt())).build(),
                                ":outDate", AttributeValue.builder().s(updated.checkOutDate().toString()).build(),
                                ":ttl", AttributeValue.builder().n(String.valueOf(updated.ttl())).build()))
                        .build())
                .build());
        // Only this booking's own unused nights are deleted; the condition protects anyone else's rows.
        for (RoomInventory inv : releasedInventories) {
            actions.add(TransactWriteItem.builder()
                    .delete(Delete.builder()
                            .tableName(tableName)
                            .key(inventoryKey(inv.roomNumber(), inv.date()))
                            .conditionExpression("bookingId = :bId")
                            .expressionAttributeValues(Map.of(
                                    ":bId", AttributeValue.builder().s(updated.bookingId()).build()))
                            .build())
                    .build());
        }
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Checked out booking {} (room {}, {} unused night(s) released)",
                    updated.bookingId(), updated.roomNumber(), releasedInventories.size());
        } catch (TransactionCanceledException ex) {
            log.warn("Check-out failed for booking {}: {}", updated.bookingId(), ex.getMessage());
            throw new BookingValidationException("Booking " + updated.bookingId()
                    + " could not be checked out (its status changed). Please refresh.");
        }
    }

    @Override
    public void recordStaffPayment(Booking updated, BigDecimal expectedPreviousAmountPaid) {
        List<TransactWriteItem> actions = new ArrayList<>();
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":confirmed", AttributeValue.builder().s(BookingStatus.CONFIRMED.name()).build());
        values.put(":checkedIn", AttributeValue.builder().s(BookingStatus.CHECKED_IN.name()).build());
        values.put(":prev", AttributeValue.builder().n(expectedPreviousAmountPaid.toPlainString()).build());
        values.put(":paid", AttributeValue.builder().n(updated.amountPaid().toPlainString()).build());
        values.put(":payStatus", AttributeValue.builder().s(updated.paymentStatus().name()).build());
        values.put(":verifiedAt", AttributeValue.builder().n(String.valueOf(updated.paymentVerifiedAt())).build());
        StringBuilder set = new StringBuilder(
                "SET amountPaid = :paid, paymentStatus = :payStatus, paymentVerifiedAt = :verifiedAt");
        if (updated.paidAt() != null) {
            set.append(", paidAt = :paidAt");
            values.put(":paidAt", AttributeValue.builder().n(String.valueOf(updated.paidAt())).build());
        }
        actions.add(TransactWriteItem.builder()
                .update(Update.builder()
                        .tableName(tableName)
                        .key(bookingKey(updated.bookingId()))
                        .updateExpression(set.toString())
                        .conditionExpression("(#status = :confirmed OR #status = :checkedIn) "
                                + "AND (amountPaid = :prev OR attribute_not_exists(amountPaid))")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(values)
                        .build())
                .build());
        actions.add(buildPendingReconciliationDelete(updated.bookingId()));
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Staff recorded / verified payment for booking {}", updated.bookingId());
        } catch (TransactionCanceledException ex) {
            log.warn("Staff payment failed for booking {}: {}", updated.bookingId(), ex.getMessage());
            throw new BookingValidationException("Booking " + updated.bookingId()
                    + " changed while the payment was being recorded. Please refresh and try again.");
        }
    }

    @Override
    public void createDirectBooking(Booking booking, List<RoomInventory> inventories, long nowEpochSeconds) {
        List<TransactWriteItem> actions = new ArrayList<>();
        actions.add(TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(mapBookingToItem(booking))
                        .conditionExpression("attribute_not_exists(PK)")
                        .build())
                .build());
        for (RoomInventory inv : inventories) {
            actions.add(buildLockPut(inv, nowEpochSeconds));
        }
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Created walk-in booking {} for room {} ({})", booking.bookingId(), booking.roomNumber(), booking.status());
        } catch (TransactionCanceledException ex) {
            log.warn("Walk-in transaction canceled for room {}: {}", booking.roomNumber(), ex.getMessage());
            throw new BookingConflictException(
                    "Room " + booking.roomNumber() + " is not available for the requested dates",
                    findConflictingDates(booking.roomNumber(),
                            inventories.stream().map(RoomInventory::date).toList(), nowEpochSeconds));
        }
    }

    @Override
    public void blockRoom(List<RoomInventory> maintenanceInventories, long nowEpochSeconds) {
        if (maintenanceInventories.isEmpty()) {
            return;
        }
        List<TransactWriteItem> actions = new ArrayList<>();
        for (RoomInventory inv : maintenanceInventories) {
            actions.add(buildLockPut(inv, nowEpochSeconds));
        }
        try {
            dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(actions).build());
            log.info("Blocked room {} for maintenance ({} night(s))",
                    maintenanceInventories.get(0).roomNumber(), maintenanceInventories.size());
        } catch (TransactionCanceledException ex) {
            int room = maintenanceInventories.get(0).roomNumber();
            log.warn("Maintenance block canceled for room {}: {}", room, ex.getMessage());
            throw new BookingConflictException(
                    "Room " + room + " cannot be blocked: it is booked, held or already blocked on some of these dates",
                    findConflictingDates(room, maintenanceInventories.stream().map(RoomInventory::date).toList(), nowEpochSeconds));
        }
    }

    @Override
    public int unblockRoom(int roomNumber, List<LocalDate> dates) {
        int released = 0;
        for (LocalDate date : dates) {
            try {
                dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                        .tableName(tableName)
                        .key(inventoryKey(roomNumber, date))
                        .conditionExpression("#status = :maint")
                        .expressionAttributeNames(Map.of("#status", "status"))
                        .expressionAttributeValues(Map.of(
                                ":maint", AttributeValue.builder().s(InventoryStatus.MAINTENANCE.name()).build()))
                        .build());
                released++;
            } catch (ConditionalCheckFailedException ignored) {
                // not blocked (free, or booked by a guest): leave it exactly as it is
            }
        }
        log.info("Unblocked room {}: {} of {} night(s) released", roomNumber, released, dates.size());
        return released;
    }

    private TransactWriteItem buildLockPut(RoomInventory inv, long nowEpochSeconds) {
        return TransactWriteItem.builder()
                .put(Put.builder()
                        .tableName(tableName)
                        .item(mapInventoryToItem(inv))
                        .conditionExpression("attribute_not_exists(PK) OR #status = :available OR (#status = :hold AND #lockExpiresAt < :now)")
                        .expressionAttributeNames(Map.of("#status", "status", "#lockExpiresAt", "lockExpiresAt"))
                        .expressionAttributeValues(Map.of(
                                ":available", AttributeValue.builder().s(InventoryStatus.AVAILABLE.name()).build(),
                                ":hold", AttributeValue.builder().s(InventoryStatus.HOLD.name()).build(),
                                ":now", AttributeValue.builder().n(String.valueOf(nowEpochSeconds)).build()))
                        .build())
                .build();
    }

    private static Map<String, AttributeValue> bookingKey(String bookingId) {
        return Map.of(
                "PK", AttributeValue.builder().s(Booking.buildPk(bookingId)).build(),
                "SK", AttributeValue.builder().s(Booking.buildSk()).build());
    }

    private static Map<String, AttributeValue> inventoryKey(int roomNumber, LocalDate date) {
        return Map.of(
                "PK", AttributeValue.builder().s(RoomInventory.buildPk(roomNumber)).build(),
                "SK", AttributeValue.builder().s(RoomInventory.buildSk(date)).build());
    }

    private List<LocalDate> findConflictingDates(int roomNumber, List<LocalDate> requestedDates, long nowEpochSeconds) {
        List<RoomInventory> current = findInventoriesForRoomAndDates(roomNumber, requestedDates);
        return current.stream()
                .filter(inv -> inv.isCurrentlyLocked(nowEpochSeconds))
                .map(RoomInventory::date)
                .sorted()
                .toList();
    }

    private Map<String, AttributeValue> mapBookingToItem(Booking b) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(Booking.buildPk(b.bookingId())).build());
        item.put("SK", AttributeValue.builder().s(Booking.buildSk()).build());
        // HMAC-SHA256 blind index over the CANONICAL phone for GSI1PK. Any write of a booking (confirm,
        // extend, pay, cancel) re-derives it, so legacy SHA-256 rows are upgraded as they are touched.
        String hashedPhone = piiEncryptionService.blindIndex(PhoneNormalizer.forStored(b.guestPhone()));
        item.put("GSI1PK", AttributeValue.builder().s(guestKey(hashedPhone)).build());
        item.put("GSI1SK", AttributeValue.builder().s(Booking.buildGsi1Sk(b.checkInDate())).build());
        item.put("bookingId", AttributeValue.builder().s(b.bookingId()).build());
        item.put("roomNumber", AttributeValue.builder().n(String.valueOf(b.roomNumber())).build());
        item.put("checkInDate", AttributeValue.builder().s(b.checkInDate().toString()).build());
        item.put("checkOutDate", AttributeValue.builder().s(b.checkOutDate().toString()).build());
        // RFC 7516 JWE encryption for PII fields at rest
        item.put("guestName", AttributeValue.builder().s(piiEncryptionService.encryptPii(b.guestName())).build());
        item.put("guestPhone", AttributeValue.builder().s(piiEncryptionService.encryptPii(b.guestPhone())).build());
        if (b.guestEmail() != null) {
            item.put("guestEmail", AttributeValue.builder().s(piiEncryptionService.encryptPii(b.guestEmail())).build());
        }
        item.put("guestCount", AttributeValue.builder().n(String.valueOf(b.guestCount())).build());
        item.put("baseRatePerNight", AttributeValue.builder().n(b.baseRatePerNight().toPlainString()).build());
        item.put("extraGuestRatePerNight", AttributeValue.builder().n(b.extraGuestRatePerNight().toPlainString()).build());
        item.put("subtotal", AttributeValue.builder().n(b.subtotal().toPlainString()).build());
        item.put("gstAmount", AttributeValue.builder().n(b.gstAmount().toPlainString()).build());
        item.put("totalAmount", AttributeValue.builder().n(b.totalAmount().toPlainString()).build());
        item.put("status", AttributeValue.builder().s(b.status().name()).build());
        item.put("createdAt", AttributeValue.builder().n(String.valueOf(b.createdAt())).build());
        item.put("expiresAt", AttributeValue.builder().n(String.valueOf(b.expiresAt())).build());
        if (b.paymentReference() != null) {
            item.put("paymentReference", AttributeValue.builder().s(b.paymentReference()).build());
        }
        if (b.confirmedAt() != null) {
            item.put("confirmedAt", AttributeValue.builder().n(String.valueOf(b.confirmedAt())).build());
        }
        item.put("checkoutTime", AttributeValue.builder().s(b.checkoutTime()).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(b.ttl())).build());
        item.put("paymentStatus", AttributeValue.builder().s(b.paymentStatus().name()).build());
        item.put("amountPaid", AttributeValue.builder().n(b.amountPaid().toPlainString()).build());
        if (b.paymentMethod() != null) {
            item.put("paymentMethod", AttributeValue.builder().s(b.paymentMethod().name()).build());
        }
        if (b.paidAt() != null) {
            item.put("paidAt", AttributeValue.builder().n(String.valueOf(b.paidAt())).build());
        }
        if (b.cancelledAt() != null) {
            item.put("cancelledAt", AttributeValue.builder().n(String.valueOf(b.cancelledAt())).build());
        }
        item.put("lateCancellation", AttributeValue.builder().bool(b.lateCancellation()).build());
        if (b.checkedInAt() != null) {
            item.put("checkedInAt", AttributeValue.builder().n(String.valueOf(b.checkedInAt())).build());
        }
        if (b.checkedOutAt() != null) {
            item.put("checkedOutAt", AttributeValue.builder().n(String.valueOf(b.checkedOutAt())).build());
        }
        if (b.paymentVerifiedAt() != null) {
            item.put("paymentVerifiedAt", AttributeValue.builder().n(String.valueOf(b.paymentVerifiedAt())).build());
        }
        return item;
    }

    private Booking mapItemToBooking(Map<String, AttributeValue> item) {
        String decryptedName = piiEncryptionService.decryptPii(item.get("guestName").s());
        String decryptedPhone = piiEncryptionService.decryptPii(item.get("guestPhone").s());
        String decryptedEmail = item.containsKey("guestEmail") ? piiEncryptionService.decryptPii(item.get("guestEmail").s()) : null;

        BookingStatus status = BookingStatus.valueOf(item.get("status").s());
        return new Booking(
                item.get("bookingId").s(),
                Integer.parseInt(item.get("roomNumber").n()),
                LocalDate.parse(item.get("checkInDate").s()),
                LocalDate.parse(item.get("checkOutDate").s()),
                decryptedName,
                decryptedPhone,
                decryptedEmail,
                Integer.parseInt(item.get("guestCount").n()),
                new BigDecimal(item.get("baseRatePerNight").n()),
                new BigDecimal(item.get("extraGuestRatePerNight").n()),
                new BigDecimal(item.get("subtotal").n()),
                new BigDecimal(item.get("gstAmount").n()),
                new BigDecimal(item.get("totalAmount").n()),
                status,
                Long.parseLong(item.get("createdAt").n()),
                Long.parseLong(item.get("expiresAt").n()),
                item.containsKey("paymentReference") ? item.get("paymentReference").s() : null,
                item.containsKey("confirmedAt") ? Long.parseLong(item.get("confirmedAt").n()) : null,
                item.containsKey("checkoutTime") ? item.get("checkoutTime").s() : "12:00 PM",
                Long.parseLong(item.get("ttl").n()),
                item.containsKey("paymentMethod") ? PaymentMethod.valueOf(item.get("paymentMethod").s()) : null,
                mapPaymentStatus(item, status),
                item.containsKey("amountPaid") ? new BigDecimal(item.get("amountPaid").n()) : BigDecimal.ZERO,
                item.containsKey("paidAt") ? Long.parseLong(item.get("paidAt").n()) : null,
                item.containsKey("cancelledAt") ? Long.parseLong(item.get("cancelledAt").n()) : null,
                item.containsKey("lateCancellation") && Boolean.TRUE.equals(item.get("lateCancellation").bool()),
                item.containsKey("checkedInAt") ? Long.parseLong(item.get("checkedInAt").n()) : null,
                item.containsKey("checkedOutAt") ? Long.parseLong(item.get("checkedOutAt").n()) : null,
                item.containsKey("paymentVerifiedAt") ? Long.parseLong(item.get("paymentVerifiedAt").n()) : null
        );
    }

    /** Rows written before Phase 2 have no paymentStatus: a confirmed one is treated as owing its balance at the desk. */
    private static PaymentStatus mapPaymentStatus(Map<String, AttributeValue> item, BookingStatus status) {
        if (item.containsKey("paymentStatus")) {
            return PaymentStatus.valueOf(item.get("paymentStatus").s());
        }
        return status == BookingStatus.CONFIRMED ? PaymentStatus.DUE_AT_CHECKIN : PaymentStatus.PENDING;
    }

    private Map<String, AttributeValue> mapInventoryToItem(RoomInventory inv) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(RoomInventory.buildPk(inv.roomNumber())).build());
        item.put("SK", AttributeValue.builder().s(RoomInventory.buildSk(inv.date())).build());
        // GSI2 (by-date) keys used by availability search
        item.put("GSI2PK", AttributeValue.builder().s(RoomInventory.buildGsi2Pk(inv.date())).build());
        item.put("GSI2SK", AttributeValue.builder().s(RoomInventory.buildGsi2Sk(inv.roomNumber())).build());
        item.put("status", AttributeValue.builder().s(inv.status().name()).build());
        item.put("bookingId", AttributeValue.builder().s(inv.bookingId()).build());
        item.put("lockExpiresAt", AttributeValue.builder().n(String.valueOf(inv.lockExpiresAt())).build());
        item.put("version", AttributeValue.builder().n(String.valueOf(inv.version())).build());
        item.put("ttl", AttributeValue.builder().n(String.valueOf(inv.ttl())).build());
        return item;
    }

    private RoomInventory mapItemToInventory(Map<String, AttributeValue> item) {
        String sk = item.get("SK").s();
        LocalDate date = LocalDate.parse(sk.replace("INVENTORY#", ""));
        int roomNumber = Integer.parseInt(item.get("PK").s().replace("ROOM#", ""));

        return new RoomInventory(
                roomNumber,
                date,
                InventoryStatus.valueOf(item.get("status").s()),
                item.containsKey("bookingId") ? item.get("bookingId").s() : "",
                item.containsKey("lockExpiresAt") ? Long.parseLong(item.get("lockExpiresAt").n()) : 0L,
                item.containsKey("version") ? Long.parseLong(item.get("version").n()) : 1L,
                item.containsKey("ttl") ? Long.parseLong(item.get("ttl").n()) : 0L
        );
    }
}
