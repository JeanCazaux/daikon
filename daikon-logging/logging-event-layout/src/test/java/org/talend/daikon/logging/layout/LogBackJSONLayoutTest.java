package org.talend.daikon.logging.layout;

import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasJsonPath;
import static com.jayway.jsonpath.matchers.JsonPathMatchers.hasNoJsonPath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.talend.daikon.logging.event.layout.LogbackJSONLayout;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;

public class LogBackJSONLayoutTest extends AbstractLayoutTest {

    @Test
    public void testDefaultLocationInfo() {
        LogbackJSONLayout layout = new LogbackJSONLayout();
        assertFalse(layout.isLocationInfo());
    }

    @Test
    public void testMarkerFields() {
        LogDetails logDetails = new LogDetails(this.getClass());
        String result = log(newEvent(logDetails), logDetails);
        // No marker results in not having any additional fields in the customInfo
        assertThat(result, hasNoJsonPath("$.['labels.accountId']"));

        Marker parent1Marker = MarkerFactory.getDetachedMarker("custom_infos");
        logDetails.setMarker(parent1Marker);
        result = log(newEvent(logDetails), logDetails);
        // A parent marker, without any children, results in not having any additional fields in the customInfo
        assertThat(result, hasNoJsonPath("$.customInfo['accountId']"));

        Marker child11Marker = MarkerFactory.getDetachedMarker("accountId:foo");
        Marker child12Marker = MarkerFactory.getDetachedMarker("userId:bar");
        parent1Marker.add(child11Marker);
        parent1Marker.add(child12Marker);
        logDetails.setMarker(parent1Marker);
        result = log(newEvent(logDetails), logDetails);

        // Children of the parent marker are used to populate the customInfo node of the logging event
        assertThat(result, hasJsonPath("$.['labels.accountId']", equalTo("foo")));
        assertThat(result, hasJsonPath("$.['labels.userId']", equalTo("bar")));
    }

    @Test
    public void testStrictMode() {
        Map<String, String> meta = Collections.singletonMap("non_ecs_meta_field", "meta");

        LogDetails logDetails = new LogDetails(this.getClass());
        logDetails.setMdc(Collections.singletonMap("non_ecs_mdc_field", "mdc"));

        String strictResult = log(newEvent(logDetails), logDetails, meta, true);
        String nonStrictResult = log(newEvent(logDetails), logDetails, meta, false);

        assertThat(strictResult, hasNoJsonPath("$.['non_ecs_mdc_field']"));
        assertThat(strictResult, hasNoJsonPath("$.['non_ecs_meta_field']"));

        assertThat(nonStrictResult, hasJsonPath("$.['non_ecs_mdc_field']", is("mdc")));
        assertThat(nonStrictResult, hasJsonPath("$.['non_ecs_meta_field']", is("meta")));
    }

    @Test
    public void testStrictModeIsDefault() {
        assertTrue(new LogbackJSONLayout().isStrictEcsMode());
    }

    @Override
    protected Object newEvent(LogDetails logDetails) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(logDetails.getClassName());
        LoggingEvent event = new LoggingEvent(this.getClass().getName(), logger, Level.DEBUG, logDetails.getLogMessage(),
                logDetails.getException(), null);
        event.setThreadName(logDetails.getThreadName());
        event.setTimeStamp(logDetails.getTimeMillis());
        event.setMDCPropertyMap(logDetails.getMdc());
        event.setMarker(logDetails.getMarker());
        StackTraceElement callerData = new StackTraceElement(logDetails.getClassName(), logDetails.getMethodName(),
                logDetails.getFileName(), logDetails.getLineNumber());
        event.setCallerData(new StackTraceElement[] { callerData });
        return event;
    }

    @Override
    protected String log(Object event, LogDetails logDetails) {
        return log(event, logDetails, Collections.emptyMap(), true);
    }

    protected String log(Object event, LogDetails logDetails, Map<String, String> metaFields, boolean strictEcs) {
        LogbackJSONLayout layout = new LogbackJSONLayout();
        layout.setLocationInfo(logDetails.isLocationInfo());
        layout.setStrictEcsMode(strictEcs);
        layout.setMetaFields(metaFields);
        layout.start();
        try {
            return layout.doLayout((LoggingEvent) event);
        } finally {
            layout.stop();
        }
    }
}
