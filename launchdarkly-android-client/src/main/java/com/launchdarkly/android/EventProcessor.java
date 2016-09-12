package com.launchdarkly.android;


import android.content.Context;
import android.util.Log;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.launchdarkly.android.LDConfig.JSON;
import static com.launchdarkly.android.Util.isInternetConnected;

class EventProcessor implements Closeable {
    private static final String TAG = "LDEventProcessor";
    private final ScheduledExecutorService scheduler;
    private final BlockingQueue<Event> queue;
    private final Consumer consumer;
    private final OkHttpClient client;
    private Context context;

    EventProcessor(Context context, LDConfig config) {
        this.context = context;
        this.queue = new ArrayBlockingQueue<>(config.getEventsCapacity());
        this.consumer = new Consumer(config);

        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat("LaunchDarkly-EventProcessor-%d")
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);
        this.scheduler.scheduleAtFixedRate(consumer, 0, config.getEventsFlushIntervalMillis(), TimeUnit.MILLISECONDS);
        client = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectionTimeoutMillis(), TimeUnit.MILLISECONDS)
                .build();
    }

    boolean sendEvent(Event e) {
        return queue.offer(e);
    }

    @Override
    public void close() throws IOException {
        scheduler.shutdown();
        this.flush();
    }

    void flush() {
        scheduler.schedule(consumer, 0, TimeUnit.SECONDS);
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
                List<Event> events = new ArrayList<>(queue.size());
                queue.drainTo(events);

                if (!events.isEmpty()) {
                    postEvents(events);
                }
            }
        }

        private void postEvents(List<Event> events) {
            String content = LDConfig.GSON.toJson(events);
            Request request = config.getRequestBuilder()
                    .url(config.getEventsUri().toString())
                    .post(RequestBody.create(JSON, content))
                    .addHeader("Content-Type", "application/json")
                    .build();

            Log.d(TAG, "Posting " + events.size() + " event(s) to " + request.url());

            Response response = null;
            try {
                response = client.newCall(request).execute();
                Log.d(TAG, "Events Response: " + response.code());
            } catch (IOException e) {
                Log.e(TAG, "Unhandled exception in LaunchDarkly client attempting to connect to URI: " + request.url(), e);
            } finally {
                if (response != null) response.close();
            }
        }
    }
}
