package com.utewedding.app.controller;

import com.utewedding.app.dto.request.ShippingAddressRequest;
import com.utewedding.app.dto.request.ShopShippingQuoteRequest;
import com.utewedding.app.service.impl.ShopShippingQuoteService;
import com.utewedding.app.service.impl.ShippingQuoteService;
import com.utewedding.app.service.web.CurrentUserContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/shipping")
@PreAuthorize("hasAnyRole('USER','CUSTOMER')")
public class ShippingQuoteController {
    private final ShippingQuoteService quotes;
    private final ShopShippingQuoteService shopQuotes;
    private final CurrentUserContext users;
    public ShippingQuoteController(ShippingQuoteService quotes, ShopShippingQuoteService shopQuotes,
                                   CurrentUserContext users) {
        this.quotes = quotes;
        this.shopQuotes = shopQuotes;
        this.users = users;
    }
    @PostMapping("/quotes") public ShippingQuoteService.QuoteResponse quote(Authentication auth,@Valid @RequestBody ShippingAddressRequest body){return quotes.quote(users.requireUserId(auth),body);}
    @PostMapping("/shop-quotes")
    public ShippingQuoteService.QuoteResponse shopQuote(Authentication auth,
                                                        @Valid @RequestBody ShopShippingQuoteRequest body) {
        java.util.UUID customerId = users.requireUserId(auth);
        if (com.utewedding.app.domain.ShopDefaults.PLATFORM_SHOP_ID.equals(body.shopId())) {
            return quotes.quotePlatformSelection(customerId, body.selectionFingerprint(), body.destination());
        }
        return shopQuotes.quote(customerId, body);
    }
}
