package com.utewedding.app.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UpdateTimelineRequest(
        @Min(1) Integer version,
        @Valid @Size(max = 100) List<TimelineItemRequest> items) {

    public UpdateTimelineRequest(List<TimelineItemRequest> items) {
        this(null, items);
    }
}
