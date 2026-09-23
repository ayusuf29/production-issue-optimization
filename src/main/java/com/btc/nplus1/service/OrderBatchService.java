package com.btc.nplus1.service;

import com.btc.nplus1.domain.CustomerOrder;
import com.btc.nplus1.domain.OrderItem;
import com.btc.nplus1.domain.User;
import com.btc.nplus1.dto.BatchImportResponse;
import com.btc.nplus1.repository.OrderRepository;
import com.btc.nplus1.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class OrderBatchService {

    private static final Logger log = LoggerFactory.getLogger(OrderBatchService.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final Counter naivePersistCounter;
    private final Counter chunkedPersistCounter;

    public OrderBatchService(OrderRepository orderRepository,
                             UserRepository userRepository,
                             MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.naivePersistCounter = meterRegistry.counter("batch.orders.persisted", "strategy", "naive_saveall");
        this.chunkedPersistCounter = meterRegistry.counter("batch.orders.persisted", "strategy", "chunked_batch");
    }

    /**
     * TOPIC 6: THE PRODUCTION FAILURE (Hibernate First-Level Cache Heap Explosion)
     * Importing 50,000 orders using orderRepository.saveAll() attaches every entity
     * into Hibernate's PersistenceContext (First-Level Cache).
     * JVM Heap steadily climbs to 100%, GC pauses skyrocket, and the JVM crashes with:
     * java.lang.OutOfMemoryError: Java heap space.
     */
    @Observed(name = "batch.import.naive", contextualName = "OrderBatchService#importOrdersNaive")
    @Transactional
    public BatchImportResponse importOrdersNaive(int count) {
        long startMs = System.currentTimeMillis();
        long initialHeapMb = getUsedHeapMb();

        log.warn("[NAIVE SAVEALL BATCH] Starting naive bulk import of {} records. Initial Heap: {} MB", count, initialHeapMb);

        User defaultUser = getOrCreateBatchUser();

        // 1. Instantiate 50,000 entities into a Java List
        List<CustomerOrder> orders = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String orderNum = "BATCH-NAIVE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + "-" + i;
            CustomerOrder order = new CustomerOrder(orderNum, "GPU-4090", "IMPORTED", Instant.now(), defaultUser);
            order.addItem(new OrderItem("GPU-4090", 1, BigDecimal.valueOf(1999.99)));
            orders.add(order);
        }

        log.warn("[NAIVE SAVEALL BATCH] List generated. Handing all {} entities to orderRepository.saveAll()...", count);

        // 2. DISASTER POINT: saveAll() loops and calls persist() on all 50k entities without clearing persistence context!
        // Every entity and its snapshot dirty-checking state remains trapped in the First-Level Cache until Tx commit.
        orderRepository.saveAll(orders);
        naivePersistCounter.increment(count);

        long peakHeapMb = getUsedHeapMb();
        long maxHeapMb = getMaxHeapMb();
        long durationMs = System.currentTimeMillis() - startMs;
        double throughput = (durationMs > 0) ? (count * 1000.0 / durationMs) : count;

        log.warn("[NAIVE SAVEALL BATCH] Finished in {} ms ({} records/sec). Peak Heap: {} MB / {} MB",
                durationMs, String.format("%.1f", throughput), peakHeapMb, maxHeapMb);

        return new BatchImportResponse(
                count,
                durationMs,
                throughput,
                "naive_saveall",
                initialHeapMb,
                peakHeapMb,
                maxHeapMb,
                "Naive saveAll completed, but retained all " + count + " entities in Hibernate 1st-level cache, driving heap to " + peakHeapMb + " MB!"
        );
    }

    /**
     * TOPIC 6: THE STEP-BY-STEP FIX (Driver Batching + Chunked Flush & Clear)
     * - Configured JDBC batch size: hibernate.jdbc.batch_size = 100
     * - Driver rewrite enabled: reWriteBatchedInserts=true
     * - Chunked Persistence: periodically calling entityManager.flush() and entityManager.clear()
     *   evicts managed entities from memory, maintaining a predictable low sawtooth heap profile.
     */
    @Observed(name = "batch.import.chunked", contextualName = "OrderBatchService#importOrdersBatch")
    @Transactional
    public BatchImportResponse importOrdersBatch(int count, int batchSize) {
        long startMs = System.currentTimeMillis();
        long initialHeapMb = getUsedHeapMb();
        int safeBatchSize = batchSize > 0 ? batchSize : 100;

        log.info("[OPTIMIZED CHUNKED BATCH] Starting bulk import of {} records with batchSize={}. Initial Heap: {} MB",
                count, safeBatchSize, initialHeapMb);

        User defaultUser = getOrCreateBatchUser();
        long peakObservedHeapMb = initialHeapMb;

        // Stream/iterate through records without retaining 50,000 managed instances in 1st-level cache
        for (int i = 0; i < count; i++) {
            String orderNum = "BATCH-OPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + "-" + i;
            CustomerOrder order = new CustomerOrder(orderNum, "GPU-4090", "IMPORTED", Instant.now(), defaultUser);
            order.addItem(new OrderItem("GPU-4090", 1, BigDecimal.valueOf(1999.99)));

            entityManager.persist(order);

            // Chunked flushing & memory clearing
            if (i > 0 && i % safeBatchSize == 0) {
                entityManager.flush();
                entityManager.clear(); // EVICTS managed entities from First-Level Cache!

                long currentHeap = getUsedHeapMb();
                if (currentHeap > peakObservedHeapMb) {
                    peakObservedHeapMb = currentHeap;
                }
            }
        }

        // Final flush and clear for the remaining trailing records in the buffer
        entityManager.flush();
        entityManager.clear();

        chunkedPersistCounter.increment(count);

        long durationMs = System.currentTimeMillis() - startMs;
        double throughput = (durationMs > 0) ? (count * 1000.0 / durationMs) : count;
        long finalHeapMb = getUsedHeapMb();
        long maxHeapMb = getMaxHeapMb();

        log.info("[OPTIMIZED CHUNKED BATCH] Finished in {} ms ({} records/sec). Peak Heap: {} MB / {} MB. Final Heap: {} MB",
                durationMs, String.format("%.1f", throughput), peakObservedHeapMb, maxHeapMb, finalHeapMb);

        return new BatchImportResponse(
                count,
                durationMs,
                throughput,
                "chunked_batch",
                initialHeapMb,
                peakObservedHeapMb,
                maxHeapMb,
                "Chunked batch import completed successfully! Memory was periodically freed via entityManager.clear(), preserving a stable low sawtooth heap pattern."
        );
    }

    /**
     * Purge all batch-imported test orders.
     */
    @Transactional
    public void cleanupBatchOrders() {
        log.info("[BATCH CLEANUP] Purging previous batch test orders...");
        orderRepository.deleteBatchOrders();
        log.info("[BATCH CLEANUP] Purged successfully.");
    }

    private User getOrCreateBatchUser() {
        return userRepository.findAll().stream().findFirst()
                .orElseGet(() -> userRepository.save(new User("batch-importer@btc.com", "Batch Ingestion Worker")));
    }

    private long getUsedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private long getMaxHeapMb() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }
}
