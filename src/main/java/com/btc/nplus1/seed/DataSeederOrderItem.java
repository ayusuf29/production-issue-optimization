package com.btc.nplus1.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class DataSeederOrderItem implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeederOrderItem.class);
    private final JdbcTemplate jdbcTemplate;

    public DataSeederOrderItem(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM customer_orders", Integer.class);
        if (count != null && count >= 100_000) {
            log.info("Database already seeded with {} orders.", count);
            return;
        }

        log.info("Seeding 100,000 orders using JDBC batching...");

        // 1. Create a dummy user
        jdbcTemplate.update(
                "INSERT INTO users (email, full_name) VALUES ('seed@btc.com', 'Seed User') ON CONFLICT DO NOTHING"
        );
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", Long.class);

        // 2. Batch insert orders
        String sql = "INSERT INTO customer_orders (order_number, created_at, user_id) VALUES (?, ?, ?)";
        Instant baseTime = Instant.parse("2026-01-01T00:00:00Z");

        int total = 100_000;
        int batchSize = 5_000;

        for (int i = 0; i < total; i += batchSize) {
            List<Object[]> batch = new ArrayList<>(batchSize);
            for (int j = 0; j < batchSize; j++) {
                int current = i + j;
                Instant timestamp = baseTime.plus(current * 10L, ChronoUnit.SECONDS);
                batch.add(new Object[]{"ORD-" + (100_000 + current), Timestamp.from(timestamp), userId});
            }
            jdbcTemplate.batchUpdate(sql, batch);
            log.info("Seeded {} of {} orders...", i + batchSize, total);
        }

        log.info("Data seeding complete. Running ANALYZE...");
        jdbcTemplate.execute("ANALYZE customer_orders;");
    }
}
