package com.btc.nplus1.seed;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.domain.OrderItem;
import com.btc.nplus1.domain.User;
import com.btc.nplus1.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Configuration
public class DataSeederConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSeederConfig.class);

    private static final String[] CATALOG_SKUS = {
            "PROD-LAPTOP-X1", "PROD-KEYBOARD-MECH", "PROD-MOUSE-WIRELESS",
            "PROD-MONITOR-4K", "PROD-CABLE-USBC", "PROD-HEADSET-PRO",
            "PROD-WEBCAM-HD", "PROD-DESK-PAD"
    };

    @Bean
    @Transactional
    public CommandLineRunner seedDatabase(UserRepository userRepository) {
        return args -> {
            if (userRepository.count() > 0) {
                log.info("Database already seeded. Skipping seeder.");
                return;
            }

            log.info("Starting database schema population & data seed...");
            long startTime = System.currentTimeMillis();

            List<User> users = new ArrayList<>();
            int totalOrdersCreated = 0;
            int totalItemsCreated = 0;

            for (int u = 1; u <= 30; u++) {
                User user = new User("user" + u + "@breakthecode.dev", "Engineer " + u);

                // Randomly generate between 40 and 60 items for this user
                int targetItemCount = ThreadLocalRandom.current().nextInt(40, 61);
                int itemsGenerated = 0;

                // Distribute items across multiple orders (each order gets 2 to 5 items)
                while (itemsGenerated < targetItemCount) {
                    Instant orderTime = Instant.now().minus(
                            ThreadLocalRandom.current().nextLong(1, 30), ChronoUnit.DAYS
                    );
                    CustomerOrder order = new CustomerOrder("ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), orderTime);

                    int itemsInThisOrder = Math.min(
                            ThreadLocalRandom.current().nextInt(2, 6),
                            targetItemCount - itemsGenerated
                    );

                    for (int i = 0; i < itemsInThisOrder; i++) {
                        String randomSku = CATALOG_SKUS[ThreadLocalRandom.current().nextInt(CATALOG_SKUS.length)];
                        int quantity = ThreadLocalRandom.current().nextInt(1, 4);
                        BigDecimal price = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(25.0, 450.0))
                                .setScale(2, RoundingMode.HALF_UP);

                        OrderItem item = new OrderItem(randomSku, quantity, price);
                        order.addItem(item);
                        itemsGenerated++;
                    }

                    user.addOrder(order);
                    totalOrdersCreated++;
                }

                totalItemsCreated += itemsGenerated;
                users.add(user);
            }

            // Saves all 30 users, cascading all CustomerOrders and OrderItems automatically
            userRepository.saveAll(users);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("Seeding complete in {} ms! Seeded 30 users, {} orders, and {} total items.",
                    elapsed, totalOrdersCreated, totalItemsCreated);
        };
    }

    @Bean
    public DataSeederOrderItem dataSeederOrderItem(JdbcTemplate jdbcTemplate) {
        return new DataSeederOrderItem(jdbcTemplate);
    }
}
