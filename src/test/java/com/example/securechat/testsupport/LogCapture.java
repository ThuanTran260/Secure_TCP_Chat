package com.example.securechat.testsupport;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Collects the formatted messages (plus any attached exception) logged to one {@link Logger}
 * so tests can assert on log lines. Always {@link #detach()} it, e.g. in {@code @AfterEach}.
 */
public final class LogCapture {
    private final Logger logger;
    private final Handler handler;
    private final List<String> messages = new CopyOnWriteArrayList<>();

    private LogCapture(Logger logger) {
        this.logger = logger;
        SimpleFormatter formatter = new SimpleFormatter();
        this.handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                String text = formatter.formatMessage(record);
                if (record.getThrown() != null) {
                    text += " | " + record.getThrown();
                }
                messages.add(text);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        this.handler.setLevel(java.util.logging.Level.ALL);
    }

    public static LogCapture attach(Logger logger) {
        LogCapture capture = new LogCapture(logger);
        logger.addHandler(capture.handler);
        return capture;
    }

    /** Snapshot of everything captured so far, in arrival order. */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** True when any captured line contains {@code fragment}. */
    public boolean contains(String fragment) {
        for (String m : messages) {
            if (m.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    public void detach() {
        logger.removeHandler(handler);
    }
}
