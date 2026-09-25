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
     * Hot Deal endpoint supporting both standard and mutex strategies.
     * Default is 'standard' (formerly 'naive') to allow immediate reproduction as seen in Episode 3 cold open.
     */
    @GetMapping("/hot-deal")
    public ResponseEntity<ProductCatalogDTO> getHotDeal(
            @RequestParam(name = "strategy", defaultValue = "standard") String strategy) {
        ProductCatalogDTO data = "mutex".equalsIgnoreCase(strategy)
                ? catalogService.getHotDealWithMutex()
                : catalogService.getHotDealStandard();
        return ResponseEntity.ok(data);
    }

    @GetMapping("/hot-deal/standard")
    public ResponseEntity<ProductCatalogDTO> getHotDealStandard() {
        return ResponseEntity.ok(catalogService.getHotDealStandard());
    }

    @GetMapping("/hot-deal/naive")
    public ResponseEntity<ProductCatalogDTO> getHotDealNaive() {
        return ResponseEntity.ok(catalogService.getHotDealStandard());
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
            @RequestParam(name = "strategy", defaultValue = "standard") String strategy) {
        ProductCatalogDTO data = "sentinel".equalsIgnoreCase(strategy)
                ? catalogService.getProductWithSentinel(productId)
                : catalogService.getProductStandard(productId);

        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(data);
    }

    @GetMapping("/product/{productId}/standard")
    public ResponseEntity<ProductCatalogDTO> getProductStandard(@PathVariable("productId") String productId) {
        ProductCatalogDTO data = catalogService.getProductStandard(productId);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(data);
    }

    @GetMapping("/product/{productId}/naive")
    public ResponseEntity<ProductCatalogDTO> getProductNaive(@PathVariable("productId") String productId) {
        return getProductStandard(productId);
    }

    @GetMapping("/product/{productId}/sentinel")
    public ResponseEntity<ProductCatalogDTO> getProductSentinel(@PathVariable("productId") String productId) {
        ProductCatalogDTO data = catalogService.getProductWithSentinel(productId);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(data);
    }

    /**
     * Simulates Midnight ERP Catalog Sync Job (Topic 3: Invalidation Cascades & TTL Jitter).
     */
    @PostMapping("/sync-erp")
    public ResponseEntity<Map<String, Object>> syncErpCatalog(
            @RequestParam(name = "strategy", defaultValue = "standard") String strategy,
            @RequestParam(name = "count", defaultValue = "20") int count) {
        return ResponseEntity.ok(catalogService.syncErpCatalog(strategy, count));
    }

    /**
     * Utility endpoint to inspect what is currently inside Redis for a specific key.
     */
    @GetMapping("/inspect")
    public ResponseEntity<Map<String, Object>> inspectCache(
            @RequestParam(name = "key", defaultValue = "catalog::hot-deal") String key) {
        return ResponseEntity.ok(catalogService.inspectKey(key));
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
