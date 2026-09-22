package com.btc.nplus1.seed;

import com.btc.nplus1.domain.Category;
import com.btc.nplus1.domain.Product;
import com.btc.nplus1.repository.CategoryRepository;
import com.btc.nplus1.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@Order(1)
public class CatalogDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogDataSeeder.class);

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CatalogDataSeeder(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Category gaming = categoryRepository.findByCode("CAT-GAMING")
                .orElseGet(() -> categoryRepository.save(new Category("High Performance Gaming", "CAT-GAMING")));
        Category electronics = categoryRepository.findByCode("CAT-ELEC")
                .orElseGet(() -> categoryRepository.save(new Category("Consumer Electronics", "CAT-ELEC")));

        // Hot Deal Product for Stampede / Thundering Herd
        if (!productRepository.existsById("hot-deal")) {
            Product hotDeal = new Product(
                    "hot-deal",
                    "BTC Ultra Gaming Rig 2026 Black Edition",
                    "Flagship extreme performance liquid-cooled gaming workstation built for Black Friday specials.",
                    BigDecimal.valueOf(2499.99),
                    100,
                    gaming
            );

            hotDeal.addDetail("Processor", "AMD Ryzen 9 9950X3D 16-Core 32-Thread");
            hotDeal.addDetail("Graphics", "NVIDIA GeForce RTX 5090 32GB GDDR7");
            hotDeal.addDetail("Memory", "64GB Dual-Channel DDR5-6400MHz EXPO");
            hotDeal.addDetail("Storage", "4TB PCIe Gen5 NVMe SSD (14,000 MB/s)");
            hotDeal.addDetail("Cooling", "Custom 360mm Liquid Loop with OLED Display");
            hotDeal.addDetail("Power", "1200W Platinum ATX 3.1 Fully Modular");
            hotDeal.addDetail("Warranty", "3-Year Advanced Replacement Warranty");

            productRepository.save(hotDeal);
        }

        // Standard sample product
        if (!productRepository.existsById("prod-1001")) {
            Product standardProd = new Product(
                    "prod-1001",
                    "Ergonomic Mechanical Keyboard",
                    "Split wireless mechanical keyboard with hot-swappable switches.",
                    BigDecimal.valueOf(189.99),
                    250,
                    electronics
            );
            standardProd.addDetail("Switches", "Gateron Oil King Linear");
            standardProd.addDetail("Connectivity", "Tri-Mode (Bluetooth 5.3, 2.4GHz, USB-C)");
            productRepository.save(standardProd);
        }

        // Seed 30 Catalog Products for Invalidation Cascades & Batch Synchronization benchmarks
        for (int i = 1; i <= 30; i++) {
            String prodId = "prod-" + i;
            if (!productRepository.existsById(prodId)) {
                Product p = new Product(
                        prodId,
                        "Enterprise Catalog Hardware Item #" + i,
                        "Standard catalog item for high concurrency benchmark.",
                        BigDecimal.valueOf(99.99 + (i * 15)),
                        300,
                        electronics
                );
                p.addDetail("SKU", "SKU-ERP-SYNC-" + i);
                p.addDetail("Hardware Revision", "v" + (i % 3 + 1) + ".0");
                productRepository.save(p);
            }
        }

        log.info("Catalog seeding finished successfully! Seeded 'hot-deal', 'prod-1001', and batch products 'prod-1' through 'prod-30'.");
    }
}
