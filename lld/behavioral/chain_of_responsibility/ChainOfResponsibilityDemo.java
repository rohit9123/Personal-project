package lld.behavioral.chain_of_responsibility;


// 1. The Request Object representing a support ticket
class Request {
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }

    private final Priority priority;
    private final String description;

    public Request(Priority priority, String description) {
        this.priority = priority;
        this.description = description;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getDescription() {
        return description;
    }
}

// 2. The Abstract Handler managing the next handler reference & standard template execution
abstract class SupportHandler {
    private SupportHandler nextHandler;

    // Sets next handler in the chain (returns the next handler for builder-like fluid chaining)
    public SupportHandler setNext(SupportHandler nextHandler) {
        this.nextHandler = nextHandler;
        return nextHandler;
    }

    // Main execution template
    public void handle(Request request) {
        if (canHandle(request)) {
            process(request);
        } else if (nextHandler != null) {
            System.out.println(getClass().getSimpleName() + " cannot handle. Escalating ticket to next tier...");
            nextHandler.handle(request);
        } else {
            System.out.println("ALERT: No handler found in the escalation chain to process: '" + request.getDescription() + "'");
        }
    }

    protected abstract boolean canHandle(Request request);
    protected abstract void process(Request request);
}

// 3. Concrete Handlers
class L1Support extends SupportHandler {
    @Override
    protected boolean canHandle(Request request) {
        return request.getPriority() == Request.Priority.LOW;
    }

    @Override
    protected void process(Request request) {
        System.out.println("L1Support (Bot) resolved issue: '" + request.getDescription() + "'");
    }
}

class L2Support extends SupportHandler {
    @Override
    protected boolean canHandle(Request request) {
        return request.getPriority() == Request.Priority.MEDIUM;
    }

    @Override
    protected void process(Request request) {
        System.out.println("L2Support (Agent) resolved issue: '" + request.getDescription() + "'");
    }
}

class L3Support extends SupportHandler {
    @Override
    protected boolean canHandle(Request request) {
        return request.getPriority() == Request.Priority.HIGH;
    }

    @Override
    protected void process(Request request) {
        System.out.println("L3Support (Engineer) resolved issue: '" + request.getDescription() + "'");
    }
}

// 4. Client Driver class
public class ChainOfResponsibilityDemo {
    public static void main(String[] args) {
        System.out.println("--- Chain of Responsibility Support Escalation Demo ---");

        // Instantiate concrete handlers
        SupportHandler bot = new L1Support();
        SupportHandler agent = new L2Support();
        SupportHandler engineer = new L3Support();

        // Chain them: Bot (L1) -> Agent (L2) -> Engineer (L3)
        // setNext returns the next handler, so we chain from bot to agent, and agent to engineer
        bot.setNext(agent).setNext(engineer);

        // Send a series of tickets to the entry point (L1 / bot)
        System.out.println("\n--- Ticket #1 ---");
        bot.handle(new Request(Request.Priority.LOW, "Reset password"));

        System.out.println("\n--- Ticket #2 ---");
        bot.handle(new Request(Request.Priority.MEDIUM, "Cannot access account dashboard"));

        System.out.println("\n--- Ticket #3 ---");
        bot.handle(new Request(Request.Priority.HIGH, "Database throwing connection pool timeout"));

        System.out.println("\n--- Ticket #4 ---");
        bot.handle(new Request(Request.Priority.CRITICAL, "Production API is down / security threat"));
    }
}
