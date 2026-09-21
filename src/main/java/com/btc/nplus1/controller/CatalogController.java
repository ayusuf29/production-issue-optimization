package com.btc.nplus1.controller;

import com.btc.nplus1.dto.ProductCatalogDTO;
import com.btc.nplus1.service.CatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Hot Deal endpoint supporting both naive and mutex strategies.
     * Default is 'naive' to allow immediate reproduction as seen in Episode 3 cold open.
     */
    @GetMapping("/hot-deal")
    public ResponseEntity<ProductCatalogDTO> getHotDeal(
            @RequestParam(name = "strategy", defaultValue = "naive") String strategy) {
        ProductCatalogDTO data = "mutex".equalsIgnoreCase(strategy)
                ? catalogService.getHotDealWithMutex()
                : catalogService.getHotDealNaive();
        return ResponseEntity.ok(data);
    }

    @GetMapping("/hot-deal/naive")
    public ResponseEntity<ProductCatalogDTO> getHotDealNaive() {
        return ResponseEntity.ok(catalogService.getHotDealNaive());
    }

    @GetMapping("/hot-deal/mutex")
    public ResponseEntity<ProductCatalogDTO> getHotDealMutex() {
        return ResponseEntity.ok(catalogService.getHotDealWithMutex());
    }

    /**
     * Product endpoint testing Cache Penetration with optional empty sentinel defense.
     */
    @GetMapping("/product/{productId}")
    public ResponseEntity<ProductCatalogDTO> getProduct(
            @PathVariable("productId") String productId,
            @RequestParam(name = "strategy", defaultValue = "naive") String strategy) {
        ProductCatalogDTO data = "sentinel".equalsIgnoreCase(strategy)
                ? catalogService.getProductWithSentinel(productId)
                : catalogService.getProductNaive(productId);

        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/product/{productId}/naive")
    public ResponseEntity<ProductCatalogDTO> getProductNaive(@PathVariable("productId") String productId) {
        ProductCatalogDTO data = catalogService.getProductNaive(productId);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(data);
    }

    @GetMapping("/product/{productId}/sentinel")
    public ResponseEntity<ProductCatalogDTO> getProductSentinel(@PathVariable("productId") String productId) {
        ProductCatalogDTO data = catalogService.getProductWithSentinel(productId);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(data);
    }

    /**
     * Utility endpoint to invalidate cache key directly via HTTP.
     */
    @PostMapping("/evict")
    public ResponseEntity<Map<String, String>> evictKey(@RequestParam(name = "key", defaultValue = "catalog::hot-deal") String key) {
        catalogService.evictKey(key);
        return ResponseEntity.ok(Map.of("message", "Evicted key: " + key));
    }
}
