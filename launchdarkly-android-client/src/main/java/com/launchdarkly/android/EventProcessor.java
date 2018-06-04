package com.launchdarkly.android;


import android.content.Context;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.launchdarkly.android.response.SummaryEventSharedPreferences;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import timber.log.Timber;

import static com.launchdarkly.android.LDConfig.JSON;
import static com.launchdarkly.android.Util.isInternetConnected;

class EventProcessor implements Closeable {
    private final BlockingQueue<Event> queue;
    private final Consumer consumer;
    private final OkHttpClient client;
    private final Context context;
    private final LDConfig config;
    private ScheduledExecutorService scheduler;
    private SummaryEvent summaryEvent = null;
    private final SummaryEventSharedPreferences summaryEventSharedPreferences;
    private long currentTimeMs = System.currentTimeMillis();

    EventProcessor(Context context, LDConfig config, SummaryEventSharedPreferences summaryEventSharedPreferences) {
        this.context = context;
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(config.getEventsCapacity());
        this.consumer = new Consumer(config);
        this.summaryEventSharedPreferences = summaryEventSharedPreferences;

        client = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(1, config.getEventsFlushIntervalMillis() * 2, TimeUnit.MILLISECONDS))
                .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    void start() {
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("LaunchDarkly-EventProcessor-%d")
                .build();
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        scheduler.scheduleAtFixedRate(consumer, 0, config.getEventsFlushIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    boolean sendEvent(Event e) {
        return queue.offer(e);
    }

    void setSummaryEvent(SummaryEvent summaryEvent) {
        this.summaryEvent = summaryEvent;
    }

    @Override
    public void close() throws IOException {
        stop();
        flush();
    }

    void flush() {
        Executors.newSingleThreadExecutor().execute(consumer);
    }

    long getCurrentTimeMs() {
        return currentTimeMs;
    }

    class Consumer implements Runnable {
        private LDConfig config;

        Consumer(LDConfig config) {
            this.config = config;
        }

        @Override
        public void run() {
            flush();
        }

        public synchronized void flush() {
            if (isInternetConnected(context)) {
                List<Event> events = new ArrayList<>(queue.size() + 1);
                queue.drainTo(events);
                if (summaryEvent != null) {
                    events.add(summaryEvent);
                    summaryEvent = null;
                    summaryEventSharedPreferences.clear();
                }

                if (!events.isEmpty()) {
                    postEvents(events);
                }
            }
        }

        private void postEvents(List<Event> events) {
            String content = config.getFilteredEventGson().toJson(events);
            Request request = config.getRequestBuilder()
                    .url(config.getEventsUri().toString())
                    .post(RequestBody.create(JSON, content))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-LaunchDarkly-Event-Schema", "3")
                    .build();

            Timber.d("Posting " + events.size() + " event(s) to " + request.url());

            Response response = null;
            try {
                response = client.newCall(request).execute();
                Timber.d("Events Response: %s", response.code());
                Timber.d("Events Response Date: %s", response.header("Date"));

                String dateString = response.header("Date");
                SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
                try {
                    Date date = sdf.parse(dateString);
                    currentTimeMs =  date.getTime();
                } catch (ParseException pe) {
                    Timber.e(pe, "Failed to parse date header");
                }
            } catch (IOException e) {
                Timber.e(e, "Unhandled exception in LaunchDarkly client attempting to connect to URI: %s", request.url());
            } finally {
                if (response != null) response.close();
            }
        }
    }
}
