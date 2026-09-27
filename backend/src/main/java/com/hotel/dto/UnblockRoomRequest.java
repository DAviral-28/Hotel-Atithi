package com.hotel.dto;

import java.time.LocalDate;

/** Optional range (inclusive nights). Omitted: every maintenance night from today onwards is released. */
public record UnblockRoomRequest(LocalDate startDate, LocalDate endDate) {}
