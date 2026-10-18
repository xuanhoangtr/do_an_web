package com.utewedding.app.controller;

import com.utewedding.app.service.impl.PublicShopService;
import com.utewedding.app.service.web.WebCatalogService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Public pages for approved vendor shops. Requests are protected by RemovedVendorRoutesFilter. */
@Controller
@RequestMapping("/shops")
public class PublicShopWebController {
    private static final int SHOP_PAGE_SIZE = 12;
    private static final int PRODUCT_PAGE_SIZE = 12;

    private final PublicShopService publicShops;
    private final WebCatalogService catalog;

    public PublicShopWebController(PublicShopService publicShops, WebCatalogService catalog) {
        this.publicShops = publicShops;
        this.catalog = catalog;
    }

    @GetMapping
    public String directory(@RequestParam(defaultValue = "") String keyword,
                            @RequestParam(defaultValue = "0") int page,
                            Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=60");
        model.addAttribute("shops", publicShops.directory(keyword, page, SHOP_PAGE_SIZE));
        model.addAttribute("keyword", keyword == null ? "" : keyword.trim());
        return "shop/directory";
    }

    @GetMapping("/{slug}")
    public String detail(@PathVariable String slug,
                         @RequestParam(defaultValue = "0") int page,
                         Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=60");
        var shop = publicShops.detail(slug);
        model.addAttribute("shop", shop);
        model.addAttribute("products", catalog.productsForShop(shop.id(), page, PRODUCT_PAGE_SIZE));
        return "shop/public-detail";
    }
}
