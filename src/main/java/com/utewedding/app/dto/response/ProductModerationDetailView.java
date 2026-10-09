package com.utewedding.app.dto.response;

import java.util.List;

public record ProductModerationDetailView(ProductModerationView product,
                                          List<ProductModerationHistoryView> history) { }
