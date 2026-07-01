package lld.problems.logging_framework;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// ============================================================================
// 1. LogLevel Enum
// ============================================================================
enum LogLevel {
    DEBUG(1), INFO(2), WARN(3), ERROR(4), FATAL(5);

    private final int priority;

    LogLevel(int priority) { this.priority = priority; }

    public int getPriority() { return priority; }

    public boolean isAtLeast(LogLevel other) {
        return this.priority >= other.priority;
    }
}

// ============================================================================
// 2. LogMessage — immutable value object
// ============================================================================
class LogMessage {
    private final LocalDateTime timestamp;
    private final LogLevel level;
    private final String source;
    private final String message;

    public LogMessage(LogLevel level, String source, String message) {
        this.timestamp = LocalDateTime.now();
        this.level = level;
        this.source = source;
        this.message = message;
    }

    public LocalDateTime getTimestamp() { return timestamp; }
    public LogLevel getLevel()         { return level; }
    public String getSource()          { return source; }
    public String getMessage()         { return message; }
}

// ============================================================================
// 3. Formatter — Strategy for output format
// ============================================================================
interface Formatter {
    String format(LogMessage msg);
}

class DefaultFormatter implements Formatter {
    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    @Override
    public String format(LogMessage msg) {
        return String.format("[%s] [%-5s] [%s] %s",
                msg.getTimestamp().format(DTF),
                msg.getLevel(),
                msg.getSource(),
                msg.getMessage());
    }
}

// ============================================================================
// 4. Appender — Strategy for output destination
// ============================================================================
interface Appender {
    void append(LogMessage msg, Formatter formatter);
}

class ConsoleAppender implements Appender {
    @Override
    public void append(LogMessage msg, Formatter formatter) {
        System.out.println(formatter.format(msg));
    }
}

class FileAppender implements Appender {
    private final String filePath;

    public FileAppender(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public void append(LogMessage msg, Formatter formatter) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(formatter.format(msg));
        } catch (IOException e) {
            System.err.println("FileAppender failed: " + e.getMessage());
        }
    }

    public String getFilePath() { return filePath; }
}

// ============================================================================
// 5. LogHandler — Chain of Responsibility
// ============================================================================
abstract class LogHandler {
    protected LogLevel handlerLevel;
    protected LogHandler nextHandler;

    public LogHandler(LogLevel handlerLevel) {
        this.handlerLevel = handlerLevel;
    }

    /** Link the next handler and return it (for chaining setNext calls). */
    public LogHandler setNext(LogHandler next) {
        this.nextHandler = next;
        return next;
    }

    /**
     * If the message's level matches this handler's level, write to appenders.
     * Then always forward to the next handler in the chain.
     */
    public void handle(LogMessage msg, List<Appender> appenders, Formatter formatter) {
        if (msg.getLevel() == this.handlerLevel) {
            write(msg, appenders, formatter);
        }
        if (nextHandler != null) {
            nextHandler.handle(msg, appenders, formatter);
        }
    }

    protected void write(LogMessage msg, List<Appender> appenders, Formatter formatter) {
        for (Appender appender : appenders) {
            appender.append(msg, formatter);
        }
    }
}

class DebugHandler extends LogHandler {
    public DebugHandler() { super(LogLevel.DEBUG); }
}

class InfoHandler extends LogHandler {
    public InfoHandler() { super(LogLevel.INFO); }
}

class WarnHandler extends LogHandler {
    public WarnHandler() { super(LogLevel.WARN); }
}

class ErrorHandler extends LogHandler {
    public ErrorHandler() { super(LogLevel.ERROR); }
}

class FatalHandler extends LogHandler {
    public FatalHandler() { super(LogLevel.FATAL); }
}

// ============================================================================
// 6. Logger — Singleton + Builder configuration
// ============================================================================
class Logger {
    private static volatile Logger instance;

    private LogLevel minLevel;
    private final List<Appender> appenders;
    private Formatter formatter;
    private final LogHandler chain;

    private Logger() {
        this.appenders = new ArrayList<>();
        this.minLevel = LogLevel.DEBUG;
        this.formatter = new DefaultFormatter();
        this.chain = buildChain();
    }

    /** Double-checked locking singleton. */
    public static Logger getInstance() {
        if (instance == null) {
            synchronized (Logger.class) {
                if (instance == null) {
                    instance = new Logger();
                }
            }
        }
        return instance;
    }

    /** Reset for demo purposes (not for production use). */
    static void resetForTest() {
        synchronized (Logger.class) {
            instance = null;
        }
    }

    /** Build the handler chain: DEBUG -> INFO -> WARN -> ERROR -> FATAL. */
    private LogHandler buildChain() {
        LogHandler debug = new DebugHandler();
        LogHandler info  = new InfoHandler();
        LogHandler warn  = new WarnHandler();
        LogHandler error = new ErrorHandler();
        LogHandler fatal = new FatalHandler();

        debug.setNext(info).setNext(warn).setNext(error).setNext(fatal);
        return debug;
    }

    // --- Configuration (called by Builder) -----------------------------------

    void configure(LogLevel minLevel, List<Appender> appenders, Formatter formatter) {
        synchronized (this) {
            this.minLevel = minLevel;
            this.appenders.clear();
            this.appenders.addAll(appenders);
            this.formatter = formatter;
        }
    }

    // --- Builder entry point -------------------------------------------------

    public static LoggerBuilder configure() {
        return new LoggerBuilder();
    }

    // --- Core logging method -------------------------------------------------

    public void log(LogLevel level, String source, String message) {
        if (!level.isAtLeast(minLevel)) return;          // level gate
        LogMessage msg = new LogMessage(level, source, message);
        chain.handle(msg, appenders, formatter);         // enter chain
    }

    // --- Convenience methods -------------------------------------------------

    public void debug(String source, String message) { log(LogLevel.DEBUG, source, message); }
    public void info(String source, String message)  { log(LogLevel.INFO, source, message); }
    public void warn(String source, String message)  { log(LogLevel.WARN, source, message); }
    public void error(String source, String message) { log(LogLevel.ERROR, source, message); }
    public void fatal(String source, String message) { log(LogLevel.FATAL, source, message); }
}

// ============================================================================
// 7. LoggerBuilder — Builder pattern for fluent configuration
// ============================================================================
class LoggerBuilder {
    private LogLevel minLevel = LogLevel.DEBUG;
    private final List<Appender> appenders = new ArrayList<>();
    private Formatter formatter = new DefaultFormatter();

    public LoggerBuilder setMinLevel(LogLevel level) {
        this.minLevel = level;
        return this;
    }

    public LoggerBuilder addAppender(Appender appender) {
        this.appenders.add(appender);
        return this;
    }

    public LoggerBuilder setFormatter(Formatter formatter) {
        this.formatter = formatter;
        return this;
    }

    public Logger build() {
        Logger logger = Logger.getInstance();
        logger.configure(this.minLevel, this.appenders, this.formatter);
        return logger;
    }
}

// ============================================================================
// 8. Demo — public entry point
// ============================================================================
public class LoggingFrameworkDemo {

    public static void main(String[] args) {
        System.out.println("=== Logging Framework Demo ===\n");

        // --- Demo 1: Console-only, min level = DEBUG (log everything) --------
        System.out.println("--- Demo 1: Console appender, min level = DEBUG ---");
        Logger.resetForTest();
        Logger logger = Logger.configure()
                .setMinLevel(LogLevel.DEBUG)
                .addAppender(new ConsoleAppender())
                .build();

        logger.debug("App",          "Application starting up...");
        logger.info("UserService",   "User rohit logged in");
        logger.warn("OrderService",  "Slow DB query detected (1200 ms)");
        logger.error("PaymentService", "Payment gateway timeout");
        logger.fatal("App",          "Out of memory — shutting down");

        // --- Demo 2: Min level = WARN (DEBUG & INFO filtered out) ------------
        System.out.println("\n--- Demo 2: Console appender, min level = WARN ---");
        Logger.resetForTest();
        logger = Logger.configure()
                .setMinLevel(LogLevel.WARN)
                .addAppender(new ConsoleAppender())
                .build();

        logger.debug("App",          "This DEBUG message is filtered out");
        logger.info("UserService",   "This INFO message is filtered out");
        logger.warn("OrderService",  "Slow DB query detected (1200 ms)");
        logger.error("PaymentService", "Payment gateway timeout");
        logger.fatal("App",          "Out of memory — shutting down");

        // --- Demo 3: Console + File appenders --------------------------------
        System.out.println("\n--- Demo 3: Console + File appender, min level = INFO ---");
        String logFile = "demo-app.log";
        Logger.resetForTest();
        logger = Logger.configure()
                .setMinLevel(LogLevel.INFO)
                .addAppender(new ConsoleAppender())
                .addAppender(new FileAppender(logFile))
                .build();

        logger.info("App",           "Server started on port 8080");
        logger.error("AuthService",  "Invalid JWT token received");
        logger.warn("CacheService",  "Cache miss rate above 40%");

        System.out.println("  (Messages also written to file: " + logFile + ")");

        // --- Demo 4: Custom formatter ----------------------------------------
        System.out.println("\n--- Demo 4: Custom JSON-style formatter ---");
        Logger.resetForTest();
        Formatter jsonFormatter = msg -> String.format(
                "{\"time\":\"%s\", \"level\":\"%s\", \"src\":\"%s\", \"msg\":\"%s\"}",
                msg.getTimestamp(), msg.getLevel(), msg.getSource(), msg.getMessage());

        logger = Logger.configure()
                .setMinLevel(LogLevel.DEBUG)
                .addAppender(new ConsoleAppender())
                .setFormatter(jsonFormatter)
                .build();

        logger.info("ApiGateway",    "Request received: GET /api/users");
        logger.error("ApiGateway",   "Upstream service returned 503");

        // --- Demo 5: Singleton verification ----------------------------------
        System.out.println("\n--- Demo 5: Singleton verification ---");
        Logger logger2 = Logger.getInstance();
        System.out.println("logger == logger2? " + (logger == logger2));  // true

        System.out.println("\n=== Demo complete ===");
    }
}
