package com.utewedding.app.service.web;

import java.util.List;
import java.util.UUID;

public interface WebWeddingService {
    List<WebViewModels.TemplateView> templates();
    List<WebViewModels.WeddingSummaryView> myWeddings(UUID ownerId);
    WebViewModels.WeddingForm form(UUID ownerId, UUID weddingId);
    WebViewModels.WeddingForm save(UUID ownerId, WebViewModels.WeddingForm form);
    WebViewModels.WeddingForm guests(UUID ownerId, UUID weddingId);
    WebViewModels.PublicWeddingView publicWedding(String slug, String guestToken);
    WebViewModels.PublicWeddingView previewByToken(String previewToken);
    void submitRsvp(String slug, WebViewModels.RsvpForm form);
    void submitWish(String slug, String senderName, String message);
}
