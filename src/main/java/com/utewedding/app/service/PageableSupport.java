package com.utewedding.app.service;

import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Shared bounds and sort allow-list for user-controlled pageable parameters. */
public final class PageableSupport {
    private PageableSupport() { }

    public static Pageable normalize(Pageable pageable, String defaultProperty, Set<String> allowedProperties) {
        int page = Math.max(0, pageable == null ? 0 : pageable.getPageNumber());
        int size = pageable == null ? 20 : Math.min(Math.max(1, pageable.getPageSize()), 100);
        Sort sort = sort(pageable == null ? Sort.unsorted() : pageable.getSort(),
                defaultProperty, allowedProperties);
        return PageRequest.of(page, size, sort);
    }

    private static Sort sort(Sort requested, String defaultProperty, Set<String> allowedProperties) {
        if (requested != null) {
            var validOrders = requested.stream()
                    .filter(order -> allowedProperties.contains(order.getProperty()))
                    .toList();
            if (!validOrders.isEmpty()) return Sort.by(validOrders);
        }
        return Sort.by(Sort.Direction.DESC, defaultProperty);
    }
}
