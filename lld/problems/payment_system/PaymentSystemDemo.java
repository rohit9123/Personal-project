package lld.problems.payment_system;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Payment System / Payment Gateway -- Low-Level Design Demo (single file, runnable).
 *
 * Patterns used:
 *   1. Strategy               -- Pluggable payment methods (CreditCard / UPI / Wallet / NetBanking)
 *   2. Factory                -- PaymentMethodFactory builds the right strategy from a PaymentRequest
 *   3. State                  -- Payment lifecycle (INITIATED -> PROCESSING -> SUCCESS/FAILED -> REFUNDED)
 *   4. Chain of Responsibility-- Validation pipeline (amount -> fraud -> rate limit); idempotency
 *                                is handled separately in PaymentService, not in the chain
 *   5. Observer               -- Notify subscribers (ledger, email, SMS) on state changes
 *
 * Cross-cutting concerns demonstrated:
 *   - Idempotency keys (retries never double-charge)
 *   - Retry with a simple gateway that fails intermittently
 *   - Thread-safety with ConcurrentHashMap + synchronized state transitions
 *
 * Run:
 *   javac -d . PaymentSystemDemo.java
 *   java lld.problems.payment_system.PaymentSystemDemo
 */
public class PaymentSystemDemo {

    // ─────────────────────────────────────────────
    // Enums
    // ─────────────────────────────────────────────
    enum PaymentMethodType { CREDIT_CARD, UPI, WALLET, NET_BANKING }

    enum PaymentStatus { INITIATED, PROCESSING, SUCCESS, FAILED, REFUNDED }

    // ─────────────────────────────────────────────
    // Value objects
    // ─────────────────────────────────────────────
    static final class Money {
        private final long amountInPaise; // store minor units to avoid floating point errors
        private final String currency;

        Money(long amountInPaise, String currency) {
            if (amountInPaise < 0) throw new IllegalArgumentException("amount cannot be negative");
            this.amountInPaise = amountInPaise;
            this.currency = currency;
        }

        /** Primary factory: pass minor units (paise) directly — no floating point involved. */
        static Money paise(long paise) {
            return new Money(paise, "INR");
        }

        /**
         * Test/demo convenience only. Accepts a double so literals like rupees(499.00) read cleanly;
         * rounds to paise immediately. Never build money from a computed double in real code —
         * the float error is already baked in before the round (rupees(0.1 + 0.2)).
         */
        static Money rupees(double rupees) {
            return new Money(Math.round(rupees * 100), "INR");
        }

        long getAmountInPaise() { return amountInPaise; }
        String getCurrency()    { return currency; }

        @Override public String toString() {
            return String.format("%s %.2f", currency, amountInPaise / 100.0);
        }
    }

    /** Immutable request coming from the client. */
    static final class PaymentRequest {
        private final String idempotencyKey;
        private final String userId;
        private final String orderId;
        private final Money amount;
        private final PaymentMethodType methodType;
        private final Map<String, String> instrument; // e.g. cardNumber, upiId, walletId

        PaymentRequest(String idempotencyKey, String userId, String orderId,
                       Money amount, PaymentMethodType methodType, Map<String, String> instrument) {
            this.idempotencyKey = idempotencyKey;
            this.userId = userId;
            this.orderId = orderId;
            this.amount = amount;
            this.methodType = methodType;
            this.instrument = instrument;
        }

        String getIdempotencyKey()       { return idempotencyKey; }
        String getUserId()               { return userId; }
        String getOrderId()              { return orderId; }
        Money getAmount()                { return amount; }
        PaymentMethodType getMethodType(){ return methodType; }
        Map<String, String> getInstrument() { return instrument; }
    }

    // ─────────────────────────────────────────────
    // Payment (aggregate root) + State transitions
    // ─────────────────────────────────────────────
    static final class Payment {
        private final String paymentId;
        private final PaymentRequest request;
        private volatile PaymentStatus status;
        private String gatewayTxnId;
        private String failureReason;

        Payment(PaymentRequest request) {
            this.paymentId = "PAY-" + UUID.randomUUID().toString().substring(0, 8);
            this.request = request;
            this.status = PaymentStatus.INITIATED;
        }

        String getPaymentId()          { return paymentId; }
        PaymentRequest getRequest()    { return request; }
        PaymentStatus getStatus()      { return status; }
        String getGatewayTxnId()       { return gatewayTxnId; }
        String getFailureReason()      { return failureReason; }

        /**
         * Guarded state machine. Only legal transitions are allowed; anything
         * else throws, so an accidental double-processing is caught loudly.
         */
        synchronized void transitionTo(PaymentStatus next) {
            if (!isValidTransition(this.status, next)) {
                throw new IllegalStateException(
                        "Illegal transition " + this.status + " -> " + next + " for " + paymentId);
            }
            this.status = next;
        }

        private static boolean isValidTransition(PaymentStatus from, PaymentStatus to) {
            switch (from) {
                case INITIATED:  return to == PaymentStatus.PROCESSING || to == PaymentStatus.FAILED;
                case PROCESSING: return to == PaymentStatus.SUCCESS || to == PaymentStatus.FAILED;
                case SUCCESS:    return to == PaymentStatus.REFUNDED;
                default:         return false; // FAILED / REFUNDED are terminal
            }
        }

        synchronized void markSuccess(String gatewayTxnId) {
            this.gatewayTxnId = gatewayTxnId;
            transitionTo(PaymentStatus.SUCCESS);
            notifyAll();
        }

        synchronized void markFailed(String reason) {
            this.failureReason = reason;
            transitionTo(PaymentStatus.FAILED);
            notifyAll();
        }
    }

    // ─────────────────────────────────────────────
    // Strategy: PaymentMethod
    // ─────────────────────────────────────────────
    interface PaymentMethod {
        /** Talks to the external gateway; returns a gateway transaction id on success. */
        GatewayResponse charge(Payment payment);
        PaymentMethodType type();
    }

    static final class GatewayResponse {
        final boolean success;
        final String gatewayTxnId;
        final String message;

        private GatewayResponse(boolean success, String gatewayTxnId, String message) {
            this.success = success;
            this.gatewayTxnId = gatewayTxnId;
            this.message = message;
        }

        static GatewayResponse ok(String txnId)      { return new GatewayResponse(true, txnId, "OK"); }
        static GatewayResponse fail(String message)  { return new GatewayResponse(false, null, message); }
    }

    /** A flaky external gateway: fails the first (failuresBeforeSuccess) attempts, then succeeds. */
    static final class ExternalGateway {
        private final String name;
        private final Map<String, AtomicInteger> attemptsByKey = new ConcurrentHashMap<>();
        private final Map<String, GatewayResponse> successResponsesByKey = new ConcurrentHashMap<>();
        private final int failuresBeforeSuccess;

        ExternalGateway(String name, int failuresBeforeSuccess) {
            this.name = name;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        GatewayResponse authorize(String idempotencyKey, Money amount) {
            GatewayResponse cached = successResponsesByKey.get(idempotencyKey);
            if (cached != null) {
                System.out.printf("      [%s] returning cached authorization (%s) for %s%n", name, cached.gatewayTxnId, amount);
                return cached;
            }

            int attempt = attemptsByKey
                    .computeIfAbsent(idempotencyKey, k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                System.out.printf("      [%s] attempt #%d for %s FAILED (transient)%n", name, attempt, amount);
                return GatewayResponse.fail("transient network error");
            }
            String txnId = name + "-" + UUID.randomUUID().toString().substring(0, 6);
            GatewayResponse successResp = GatewayResponse.ok(txnId);
            successResponsesByKey.put(idempotencyKey, successResp);
            System.out.printf("      [%s] attempt #%d for %s AUTHORIZED (%s)%n", name, attempt, amount, txnId);
            return successResp;
        }
    }

    static final class CreditCardPayment implements PaymentMethod {
        private final ExternalGateway gateway;
        CreditCardPayment(ExternalGateway gateway) { this.gateway = gateway; }

        @Override public GatewayResponse charge(Payment payment) {
            String card = payment.getRequest().getInstrument().get("cardNumber");
            System.out.println("    Charging credit card ****" + card.substring(card.length() - 4));
            return gateway.authorize(payment.getRequest().getIdempotencyKey(), payment.getRequest().getAmount());
        }
        @Override public PaymentMethodType type() { return PaymentMethodType.CREDIT_CARD; }
    }

    static final class UpiPayment implements PaymentMethod {
        private final ExternalGateway gateway;
        UpiPayment(ExternalGateway gateway) { this.gateway = gateway; }

        @Override public GatewayResponse charge(Payment payment) {
            System.out.println("    Collecting via UPI " + payment.getRequest().getInstrument().get("upiId"));
            return gateway.authorize(payment.getRequest().getIdempotencyKey(), payment.getRequest().getAmount());
        }
        @Override public PaymentMethodType type() { return PaymentMethodType.UPI; }
    }

    static final class WalletPayment implements PaymentMethod {
        private final ExternalGateway gateway;
        WalletPayment(ExternalGateway gateway) { this.gateway = gateway; }

        @Override public GatewayResponse charge(Payment payment) {
            System.out.println("    Debiting wallet " + payment.getRequest().getInstrument().get("walletId"));
            return gateway.authorize(payment.getRequest().getIdempotencyKey(), payment.getRequest().getAmount());
        }
        @Override public PaymentMethodType type() { return PaymentMethodType.WALLET; }
    }

    static final class NetBankingPayment implements PaymentMethod {
        private final ExternalGateway gateway;
        NetBankingPayment(ExternalGateway gateway) { this.gateway = gateway; }

        @Override public GatewayResponse charge(Payment payment) {
            System.out.println("    Redirecting to bank " + payment.getRequest().getInstrument().get("bankCode"));
            return gateway.authorize(payment.getRequest().getIdempotencyKey(), payment.getRequest().getAmount());
        }
        @Override public PaymentMethodType type() { return PaymentMethodType.NET_BANKING; }
    }

    // ─────────────────────────────────────────────
    // Factory: PaymentMethodFactory
    // ─────────────────────────────────────────────
    static final class PaymentMethodFactory {
        private final Map<PaymentMethodType, PaymentMethod> methods = new EnumMap<>(PaymentMethodType.class);

        PaymentMethodFactory(ExternalGateway cardGateway, ExternalGateway upiGateway) {
            // Gateway assignment is arbitrary for the demo (only two stub gateways exist);
            // in production each instrument routes to its own PSP / bank rail.
            methods.put(PaymentMethodType.CREDIT_CARD, new CreditCardPayment(cardGateway));
            methods.put(PaymentMethodType.UPI, new UpiPayment(upiGateway));
            methods.put(PaymentMethodType.WALLET, new WalletPayment(upiGateway));
            methods.put(PaymentMethodType.NET_BANKING, new NetBankingPayment(cardGateway));
        }

        PaymentMethod create(PaymentMethodType type) {
            PaymentMethod method = methods.get(type);
            if (method == null) {
                throw new IllegalArgumentException("Unsupported method: " + type);
            }
            return method;
        }
    }

    // ─────────────────────────────────────────────
    // Chain of Responsibility: Validation pipeline
    // ─────────────────────────────────────────────
    static abstract class Validator {
        private Validator next;

        Validator linkWith(Validator next) { this.next = next; return next; }

        /** Returns null if valid, else a failure reason. Stops the chain on first failure. */
        final String validate(PaymentRequest request) {
            String reason = doValidate(request);
            if (reason != null) return reason;
            return next == null ? null : next.validate(request);
        }

        protected abstract String doValidate(PaymentRequest request);
    }

    static final class AmountValidator extends Validator {
        @Override protected String doValidate(PaymentRequest r) {
            if (r.getAmount().getAmountInPaise() <= 0) return "amount must be positive";
            if (r.getAmount().getAmountInPaise() > 10_000_00) return "amount exceeds per-txn limit";
            return null;
        }
    }

    static final class FraudValidator extends Validator {
        @Override protected String doValidate(PaymentRequest r) {
            // toy rule: block a known bad card prefix
            String card = r.getInstrument().get("cardNumber");
            if (card != null && card.startsWith("0000")) return "flagged by fraud engine";
            return null;
        }
    }

    static final class RateLimitValidator extends Validator {
        private final Map<String, AtomicInteger> hitsPerUser = new ConcurrentHashMap<>();
        private final int maxPerWindow;

        RateLimitValidator(int maxPerWindow) { this.maxPerWindow = maxPerWindow; }

        @Override protected String doValidate(PaymentRequest r) {
            int hits = hitsPerUser.computeIfAbsent(r.getUserId(), k -> new AtomicInteger(0)).incrementAndGet();
            if (hits > maxPerWindow) return "rate limit exceeded for user " + r.getUserId();
            return null;
        }
    }

    // ─────────────────────────────────────────────
    // Observer: notify on lifecycle changes
    // ─────────────────────────────────────────────
    interface PaymentObserver {
        void onStatusChanged(Payment payment);
    }

    static final class LedgerObserver implements PaymentObserver {
        @Override public void onStatusChanged(Payment p) {
            if (p.getStatus() == PaymentStatus.SUCCESS) {
                System.out.printf("    [Ledger] recorded %s credit for %s (txn %s)%n",
                        p.getRequest().getAmount(), p.getRequest().getOrderId(), p.getGatewayTxnId());
            } else if (p.getStatus() == PaymentStatus.REFUNDED) {
                System.out.printf("    [Ledger] recorded refund for %s%n", p.getPaymentId());
            }
        }
    }

    static final class NotificationObserver implements PaymentObserver {
        @Override public void onStatusChanged(Payment p) {
            System.out.printf("    [Notify] user %s -> payment %s is %s%n",
                    p.getRequest().getUserId(), p.getPaymentId(), p.getStatus());
        }
    }

    // ─────────────────────────────────────────────
    // Orchestrator: PaymentService
    // ─────────────────────────────────────────────
    static final class PaymentService {
        private final PaymentMethodFactory factory;
        private final Validator validationChain;
        private final List<PaymentObserver> observers = new CopyOnWriteArrayList<>();
        private final int maxRetries;

        // Idempotency store: key -> already-completed or in-progress Payment
        private final Map<String, Payment> processedByKey = new ConcurrentHashMap<>();

        PaymentService(PaymentMethodFactory factory, Validator validationChain, int maxRetries) {
            this.factory = factory;
            this.validationChain = validationChain;
            this.maxRetries = maxRetries;
        }

        void register(PaymentObserver observer) { observers.add(observer); }

        private void notifyObservers(Payment p) {
            for (PaymentObserver o : observers) o.onStatusChanged(p);
        }

        Payment pay(PaymentRequest request) {
            // Create a potential new payment and put it in the store atomically.
            Payment payment = new Payment(request);
            Payment existing = processedByKey.putIfAbsent(request.getIdempotencyKey(), payment);

            // 1. Idempotency handling
            if (existing != null) {
                System.out.println("    [Idempotency] detected concurrent or cached request for key " + request.getIdempotencyKey());
                synchronized (existing) {
                    // If another thread is still processing it, wait for it to complete.
                    while (existing.getStatus() == PaymentStatus.INITIATED || existing.getStatus() == PaymentStatus.PROCESSING) {
                        try {
                            existing.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for concurrent payment to finish", e);
                        }
                    }
                }
                return existing;
            }

            System.out.println("  -> " + payment.getPaymentId() + " for order " + request.getOrderId()
                    + " (" + request.getAmount() + ", " + request.getMethodType() + ")");

            // 2. Validation chain.
            String failure = validationChain.validate(request);
            if (failure != null) {
                payment.markFailed(failure);
                System.out.println("    [Validation] rejected: " + failure);
                notifyObservers(payment);
                return payment;
            }

            // 3. Charge via the chosen strategy, with retries. The gateway keys on the
            //    idempotency key, so retries never double-authorize.
            payment.transitionTo(PaymentStatus.PROCESSING);
            PaymentMethod method = factory.create(request.getMethodType());

            GatewayResponse response = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                response = method.charge(payment);
                if (response.success) break;
                System.out.println("    [Retry] attempt " + attempt + " failed: " + response.message);
            }

            if (response != null && response.success) {
                payment.markSuccess(response.gatewayTxnId);
            } else {
                payment.markFailed(response == null ? "no response" : response.message);
            }

            notifyObservers(payment);
            return payment;
        }

        Payment refund(Payment payment) {
            if (payment.getStatus() != PaymentStatus.SUCCESS) {
                System.out.println("    [Refund] only SUCCESS payments can be refunded (" + payment.getPaymentId() + ")");
                return payment;
            }
            payment.transitionTo(PaymentStatus.REFUNDED);
            System.out.println("    [Refund] " + payment.getPaymentId() + " refunded " + payment.getRequest().getAmount());
            notifyObservers(payment);
            return payment;
        }
    }

    // ─────────────────────────────────────────────
    // Demo driver
    // ─────────────────────────────────────────────
    public static void main(String[] args) {
        // Gateways: card gateway fails 2 attempts before success, upi always succeeds.
        ExternalGateway cardGateway = new ExternalGateway("CardGW", 2);
        ExternalGateway upiGateway = new ExternalGateway("UpiGW", 0);

        PaymentMethodFactory factory = new PaymentMethodFactory(cardGateway, upiGateway);

        Validator chain = new AmountValidator();
        chain.linkWith(new FraudValidator()).linkWith(new RateLimitValidator(3));

        PaymentService service = new PaymentService(factory, chain, /*maxRetries*/ 3);
        service.register(new LedgerObserver());
        service.register(new NotificationObserver());

        System.out.println("=== 1. Successful UPI payment ===");
        PaymentRequest upiReq = new PaymentRequest(
                "key-upi-1", "user-1", "order-1", Money.rupees(499.00),
                PaymentMethodType.UPI, Map.of("upiId", "rohit@bank"));
        Payment p1 = service.pay(upiReq);

        System.out.println("\n=== 2. Card payment that succeeds after retries ===");
        PaymentRequest cardReq = new PaymentRequest(
                "key-card-1", "user-1", "order-2", Money.rupees(1200.50),
                PaymentMethodType.CREDIT_CARD, Map.of("cardNumber", "4242424242424242"));
        Payment p2 = service.pay(cardReq);

        System.out.println("\n=== 3. Idempotent retry of the same card request ===");
        service.pay(cardReq); // returns cached result, no re-charge

        System.out.println("\n=== 4. Fraud-blocked card ===");
        PaymentRequest fraudReq = new PaymentRequest(
                "key-card-2", "user-2", "order-3", Money.rupees(50.00),
                PaymentMethodType.CREDIT_CARD, Map.of("cardNumber", "0000111122223333"));
        service.pay(fraudReq);

        System.out.println("\n=== 5. Amount over limit ===");
        PaymentRequest bigReq = new PaymentRequest(
                "key-upi-2", "user-3", "order-4", Money.rupees(99999.00),
                PaymentMethodType.UPI, Map.of("upiId", "big@bank"));
        service.pay(bigReq);

        System.out.println("\n=== 6. Refund a successful payment ===");
        service.refund(p1);

        System.out.println("\n=== Summary ===");
        System.out.println("  " + p1.getPaymentId() + " -> " + p1.getStatus());
        System.out.println("  " + p2.getPaymentId() + " -> " + p2.getStatus()
                + " (gateway txn " + p2.getGatewayTxnId() + ")");
    }
}
