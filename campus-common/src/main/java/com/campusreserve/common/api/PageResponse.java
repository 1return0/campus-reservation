package com.campusreserve.common.api;

import java.util.List;

/** A stable, reusable page envelope for list endpoints. */
public record PageResponse<T>(
        List<T> items,
        long page,
        long size,
        long total
) {
}
