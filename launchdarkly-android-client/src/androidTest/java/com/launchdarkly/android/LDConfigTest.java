package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class LDConfigTest {

    @Test
    public void TestBuilderDefaults() {
        LDConfig config = new LDConfig.Builder().build();
        assertTrue(config.isStream());
        assertFalse(config.isOffline());

        assertEquals(LDConfig.DEFAULT_BASE_URI, config.getBaseUri());
        assertEquals(LDConfig.DEFAULT_EVENTS_URI, config.getEventsUri());
        assertEquals(LDConfig.DEFAULT_STREAM_URI, config.getStreamUri());

        assertEquals(LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, config.getConnectionTimeoutMillis());
        assertEquals(LDConfig.DEFAULT_EVENTS_CAPACITY, config.getEventsCapacity());
        assertEquals(LDConfig.DEFAULT_FLUSH_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());

        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertEquals(false, config.isDisableBackgroundPolling());

        assertEquals(null, config.getMobileKey());
        assertFalse(config.inlineUsersInEvents());
    }


    @Test
    public void TestBuilderStreamDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledCustomIntervals() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1)
                .setBackgroundPollingIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 2)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 2, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledBackgroundUpdatingDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setDisableBackgroundUpdating(true)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertTrue(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.MIN_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledBackgroundPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setBackgroundPollingIntervalMillis(LDConfig.MIN_BACKGROUND_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.MIN_BACKGROUND_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderUseReportDefaultGet() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertFalse(config.isUseReport());
    }

    @Test
    public void TestBuilderUseReporSetToGet() {
        LDConfig config = new LDConfig.Builder()
                .setUseReport(false)
                .build();

        assertFalse(config.isUseReport());
    }

    @Test
    public void TestBuilderUseReportSetToReport() {
        LDConfig config = new LDConfig.Builder()
                .setUseReport(true)
                .build();

        assertTrue(config.isUseReport());
    }

    @Test
    public void TestBuilderAllAttributesPrivate() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertFalse(config.allAttributesPrivate());

        config = new LDConfig.Builder()
                .allAttributesPrivate()
                .build();

        assertTrue(config.allAttributesPrivate());
    }

    @Test
    public void TestBuilderPrivateAttributesList() {
        LDConfig config = new LDConfig.Builder()
                .build();

        assertEquals(config.getPrivateAttributeNames().size(), 0);

        config = new LDConfig.Builder()
                .setPrivateAttributeNames(new HashSet<String>() {
                    {
                        add("email");
                        add("name");
                    }
                })
                .build();

        assertEquals(config.getPrivateAttributeNames().size(), 2);
    }

}