package com.doggeon.jobrecommendation.recommendation;

import java.util.List;

/** Stable public pagination contract, independent of Spring Data's Page serialization. */
public record JobPageResponse(List<JobResponse> content, int page, int size,
                              long totalElements, int totalPages, boolean hasNext) {
}
