package org.talend.logging.audit.impl;

import org.talend.logging.audit.Context;
import org.talend.logging.audit.ContextBuilder;
import org.talend.logging.audit.LogLevel;

import java.util.Map;

/**
 *
 */
public abstract class AbstractAuditLoggerBase implements AuditLoggerBase {

    private static String formatMessage(String message, Map<String, Object> mdcContext) {
        if (mdcContext == null) {
            return message;
        }

        String formattedMessage = message;
        for (Map.Entry<String, Object> entry : mdcContext.entrySet()) {
            formattedMessage = formattedMessage.replace('{' + entry.getKey() + '}', String.valueOf(entry.getValue()));
        }
        return formattedMessage;
    }

    public void log(LogLevel level, String category, Context context, Throwable throwable, String message) {
        if (category == null) {
            throw new IllegalArgumentException("category cannot be null");
        }

        String categoryNormalized = category.trim().toLowerCase();
        if (categoryNormalized.isEmpty()) {
            throw new IllegalArgumentException("category cannot be empty or blank");
        }

        String actualMessage = message == null && throwable != null ? throwable.getMessage() : message;
        if (actualMessage == null) {
            throw new IllegalArgumentException("message cannot be null");
        }

        logInternal(level, categoryNormalized, context, throwable, actualMessage);
    }

    private void logInternal(LogLevel level, String category, Context context, Throwable throwable, String message) {
        // creating copy of passed context to be able to modify it
        Context actualContext = context == null ? ContextBuilder.emptyContext() : ContextBuilder.create(context).build();
        Map<String, Object> enrichedContext = getEnricher().enrich(category, actualContext);

        final AbstractBackend logger = getLogger();
        final Map<String, Object> oldContext = logger.getCopyOfContextMap();
        final Map<String, Object> completeContext = logger.setNewContext(oldContext, enrichedContext);
        try {
            final String formattedMessage = formatMessage(message, completeContext);

            logger.log(category, level, formattedMessage, throwable);
        } finally {
            logger.resetContext(oldContext);
        }
    }

    protected abstract AbstractBackend getLogger();

    protected abstract ContextEnricher getEnricher();
}
