package com.btc.nplus1.service;

import com.btc.nplus1.dto.PaymentDetails;
import com.btc.nplus1.dto.PaymentResult;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    @Observed(name = "payment.gateway.process", contextualName = "PaymentClient#processPayment")
    public PaymentResult processPayment(PaymentDetails paymentDetails) {
        double amount = (paymentDetails != null) ? paymentDetails.amount() : 1999.99;
        log.info("[PAYMENT GATEWAY] Initiating external HTTP payment call for amount: ${}. Simulating 4000ms network latency...", amount);

        try {
            // Simulated 4-second external third-party payment gateway latency
            Thread.sleep(4000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[PAYMENT GATEWAY] Payment call interrupted!");
            return new PaymentResult(false, null, "Payment processing interrupted");
        }

        String txId = "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[PAYMENT GATEWAY] Payment successfully authorized after 4000ms! Gateway TxID: {}", txId);
        return new PaymentResult(true, txId, "Payment authorized successfully");
    }
}
