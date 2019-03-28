package com.launchdarkly.android;

import android.app.Application;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.support.annotation.NonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.JsonElement;
import com.launchdarkly.android.flagstore.Flag;

import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import timber.log.Timber;

import static com.launchdarkly.android.Util.isClientConnected;
import static com.launchdarkly.android.tls.TLSUtils.patchTLSIfNeeded;

/**
 * Client for accessing LaunchDarkly's Feature Flag system. This class enforces a singleton pattern.
 * The main entry point is the {@link #init(Application, LDConfig, LDUser)} method.
 */
public class LDClient implements LDClientInterface, Closeable {

    private static final String INSTANCE_ID_KEY = "instanceId";
    // Upon client init will get set to a Unique id per installation used when creating anonymous users
    private static String instanceId = "UNKNOWN_ANDROID";
    private static Map<String, LDClient> instances = null;

    private static final long MAX_RETRY_TIME_MS = 3_600_000; // 1 hour
    private static final long RETRY_TIME_MS = 1_000; // 1 second

    private final String environmentName;
    private final WeakReference<Application> application;
    private final LDConfig config;
    private final UserManager userManager;
    private final EventProcessor eventProcessor;
    private final UpdateProcessor updateProcessor;
    private final FeatureFlagFetcher fetcher;
    private final Throttler throttler;
    private final Foreground.Listener foregroundListener;
    private ConnectivityReceiver connectivityReceiver;

    private volatile boolean isOffline = false;
    private volatile boolean isAppForegrounded = true;

    /**
     * Initializes the singleton/primary instance. The result is a {@link Future} which
     * will complete once the client has been initialized with the latest feature flag values. For
     * immediate access to the Client (possibly with out of date feature flags), it is safe to ignore
     * the return value of this method, and afterward call {@link #get()}
     * <p/>
     * If the client has already been initialized, is configured for offline mode, or the device is
     * not connected to the internet, this method will return a {@link Future} that is
     * already in the completed state.
     *
     * @param application Your Android application.
     * @param config      Configuration used to set up the client
     * @param user        The user used in evaluating feature flags
     * @return a {@link Future} which will complete once the client has been initialized.
     */
    public static Future<LDClient> init(@NonNull Application application, @NonNull LDConfig config, @NonNull LDUser user) {
        if (application == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid application"));
        }
        if (config == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid configuration"));
        }
        if (user == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("Client initialization requires a valid user"));
        }

        if (instances != null) {
            Timber.w("LDClient.init() was called more than once! returning primary instance.");
            SettableFuture<LDClient> settableFuture = SettableFuture.create();
            settableFuture.set(instances.get(LDConfig.primaryEnvironmentName));
            return settableFuture;
        }
        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }

        patchTLSIfNeeded(application);

        ConnectivityManager cm = (ConnectivityManager) application.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean deviceConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();

        instances = new HashMap<>();

        Map<String, ListenableFuture<Void>> updateFutures = new HashMap<>();

        SharedPreferences instanceIdSharedPrefs =
                application.getSharedPreferences(LDConfig.SHARED_PREFS_BASE_KEY + "id", Context.MODE_PRIVATE);

        if (!instanceIdSharedPrefs.contains(INSTANCE_ID_KEY)) {
            String uuid = UUID.randomUUID().toString();
            Timber.i("Did not find existing instance id. Saving a new one");
            SharedPreferences.Editor editor = instanceIdSharedPrefs.edit();
            editor.putString(INSTANCE_ID_KEY, uuid);
            editor.apply();
        }

        instanceId = instanceIdSharedPrefs.getString(INSTANCE_ID_KEY, instanceId);
        Timber.i("Using instance id: %s", instanceId);

        Migration.migrateWhenNeeded(application, config);

        for (Map.Entry<String, String> mobileKeys : config.getMobileKeys().entrySet()) {
            final LDClient instance = new LDClient(application, config, mobileKeys.getKey());
            instance.userManager.setCurrentUser(user);

            instances.put(mobileKeys.getKey(), instance);

            if (instance.isOffline() || !deviceConnected)
                continue;

            instance.eventProcessor.start();
            updateFutures.put(mobileKeys.getKey(), instance.updateProcessor.start());
            instance.sendEvent(new IdentifyEvent(user));
        }

        ArrayList<ListenableFuture<Void>> online = new ArrayList<>();

        for (Map.Entry<String, ListenableFuture<Void>> entry : updateFutures.entrySet()) {
            if (!instances.get(entry.getKey()).isOffline()) {
                online.add(entry.getValue());
            }
        }

        ListenableFuture<List<Void>> allFuture = Futures.allAsList(online);

        return Futures.transform(allFuture, new Function<List<Void>, LDClient>() {
            @Override
            public LDClient apply(List<Void> input) {
                return instances.get(LDConfig.primaryEnvironmentName);
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Initializes the singleton instance and blocks for up to <code>startWaitSeconds</code> seconds
     * until the client has been initialized. If the client does not initialize within
     * <code>startWaitSeconds</code> seconds, it is returned anyway and can be used, but may not
     * have fetched the most recent feature flag values.
     *
     * @param application      Your Android application.
     * @param config           Configuration used to set up the client
     * @param user             The user used in evaluating feature flags
     * @param startWaitSeconds Maximum number of seconds to wait for the client to initialize
     * @return The primary LDClient instance
     */
    public static synchronized LDClient init(Application application, LDConfig config, LDUser user, int startWaitSeconds) {
        Timber.i("Initializing Client and waiting up to %s for initialization to complete", startWaitSeconds);
        Future<LDClient> initFuture = init(application, config, user);
        try {
            return initFuture.get(startWaitSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException e) {
            Timber.e(e, "Exception during Client initialization");
        } catch (TimeoutException e) {
            Timber.w("Client did not successfully initialize within %s seconds. It could be taking longer than expected to start up", startWaitSeconds);
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    /**
     * @return the singleton instance.
     * @throws LaunchDarklyException if {@link #init(Application, LDConfig, LDUser)} has not been called.
     */
    public static LDClient get() throws LaunchDarklyException {
        if (instances == null) {
            Timber.e("LDClient.get() was called before init()!");
            throw new LaunchDarklyException("LDClient.get() was called before init()!");
        }
        return instances.get(LDConfig.primaryEnvironmentName);
    }

    static Set<String> getEnvironmentNames() throws LaunchDarklyException {
        if (instances == null) {
            Timber.e("LDClient.getEnvironmentNames() was called before init()!");
            throw new LaunchDarklyException("LDClient.getEnvironmentNames() was called before init()!");
        }
        return instances.keySet();
    }

    public static LDClient getForMobileKey(String keyName) throws LaunchDarklyException {
        if (instances == null) {
            Timber.e("LDClient.getForMobileKey() was called before init()!");
            throw new LaunchDarklyException("LDClient.getForMobileKey() was called before init()!");
        }
        if (!(instances.containsKey(keyName))) {
            throw new LaunchDarklyException("LDClient.getForMobileKey() called with invalid keyName");
        }
        return instances.get(keyName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config) {
        this(application, config, LDConfig.primaryEnvironmentName);
    }

    @VisibleForTesting
    protected LDClient(final Application application, @NonNull final LDConfig config, final String environmentName) {
        Timber.i("Creating LaunchDarkly client. Version: %s", BuildConfig.VERSION_NAME);
        this.config = config;
        this.isOffline = config.isOffline();
        this.application = new WeakReference<>(application);
        this.environmentName = environmentName;
        this.fetcher = HttpFeatureFlagFetcher.newInstance(application, config, environmentName);
        this.userManager = UserManager.newInstance(application, fetcher, environmentName, config.getMobileKeys().get(environmentName));

        Foreground foreground = Foreground.get(application);
        foregroundListener = new Foreground.Listener() {
            @Override
            public void onBecameForeground() {
                PollingUpdater.stop(application);
                isAppForegrounded = true;
                if (isClientConnected(application, environmentName)) {
                    startForegroundUpdating();
                }
            }

            @Override
            public void onBecameBackground() {
                stopForegroundUpdating();
                isAppForegrounded = false;
                startBackgroundPolling();
            }
        };
        foreground.addListener(foregroundListener);

        if (config.isStream()) {
            this.updateProcessor = new StreamUpdateProcessor(config, userManager, environmentName);
        } else {
            Timber.i("Streaming is disabled. Starting LaunchDarkly Client in polling mode");
            this.updateProcessor = new PollingUpdateProcessor(application, userManager, config);
        }
        eventProcessor = new EventProcessor(application, config, userManager.getSummaryEventSharedPreferences(), environmentName);

        throttler = new Throttler(new Runnable() {
            @Override
            public void run() {
                setOnlineStatus();
            }
        }, RETRY_TIME_MS, MAX_RETRY_TIME_MS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            connectivityReceiver = new ConnectivityReceiver();
            IntentFilter filter = new IntentFilter(ConnectivityReceiver.CONNECTIVITY_CHANGE);
            application.registerReceiver(connectivityReceiver, filter);
        }
    }

    @Override
    public void track(String eventName, JsonElement data) {
        if (config.inlineUsersInEvents()) {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser(), data));
        } else {
            sendEvent(new CustomEvent(eventName, userManager.getCurrentUser().getKeyAsString(), data));
        }
    }

    @Override
    public void track(String eventName) {
        track(eventName, null);
    }

    @Override
    public synchronized Future<Void> identify(LDUser user) {
        return LDClient.identifyInstances(user);
    }

    private synchronized ListenableFuture<Void> identifyInternal(LDUser user) {
        if (user == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("User cannot be null"));
        }

        if (user.getKey() == null) {
            Timber.w("identify called with null user or null user key!");
        }

        ListenableFuture<Void> doneFuture;
        userManager.setCurrentUser(user);

        if (!config.isStream()) {
            doneFuture = userManager.updateCurrentUser();
        } else {
            doneFuture = updateProcessor.restart();
        }

        sendEvent(new IdentifyEvent(user));

        return doneFuture;
    }

    private static synchronized Future<Void> identifyInstances(LDUser user) {
        if (user == null) {
            return Futures.immediateFailedFuture(new LaunchDarklyException("User cannot be null"));
        }

        ArrayList<ListenableFuture<Void>> futures = new ArrayList<>();

        for (LDClient client : instances.values()) {
            futures.add(client.identifyInternal(user));
        }

        return Futures.transform(Futures.allAsList(futures), new Function<List<Void>, Void>() {
            @Override
            public Void apply(List<Void> input) {
                return null;
            }
        }, MoreExecutors.directExecutor());
    }

    @Override
    public Map<String, ?> allFlags() {
        Map<String, Object> result = new HashMap<>();
        List<Flag> flags = userManager.getCurrentUserFlagStore().getAllFlags();
        for (Flag flag : flags) {
            JsonElement jsonVal = flag.getValue();
            if (jsonVal == null || jsonVal.isJsonNull()) {
                result.put(flag.getKey(), null);
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isBoolean()) {
                result.put(flag.getKey(), jsonVal.getAsBoolean());
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isNumber()) {
                result.put(flag.getKey(), jsonVal.getAsFloat());
            } else if (jsonVal.isJsonPrimitive() && jsonVal.getAsJsonPrimitive().isString()) {
                result.put(flag.getKey(), jsonVal.getAsString());
            } else {
                result.put(flag.getKey(), jsonVal);
            }
        }
        return result;
    }

    @Override
    public Boolean boolVariation(String flagKey, Boolean fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.BOOLEAN, false).getValue();
    }

    @Override
    public EvaluationDetail<Boolean> boolVariationDetail(String flagKey, Boolean fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.BOOLEAN, true);
    }

    @Override
    public Integer intVariation(String flagKey, Integer fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.INT, false).getValue();
    }

    @Override
    public EvaluationDetail<Integer> intVariationDetail(String flagKey, Integer fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.INT, true);
    }

    @Override
    public Float floatVariation(String flagKey, Float fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.FLOAT, false).getValue();
    }

    @Override
    public EvaluationDetail<Float> floatVariationDetail(String flagKey, Float fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.FLOAT, true);
    }

    @Override
    public String stringVariation(String flagKey, String fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.STRING, false).getValue();
    }

    @Override
    public EvaluationDetail<String> stringVariationDetail(String flagKey, String fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.STRING, true);
    }

    @Override
    public JsonElement jsonVariation(String flagKey, JsonElement fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.JSON, false).getValue();
    }

    @Override
    public EvaluationDetail<JsonElement> jsonVariationDetail(String flagKey, JsonElement fallback) {
        return variationDetailInternal(flagKey, fallback, ValueTypes.JSON, true);
    }

    private <T> EvaluationDetail<T> variationDetailInternal(String flagKey, T fallback, ValueTypes.Converter<T> typeConverter, boolean includeReasonInEvent) {
        if (flagKey == null) {
            Timber.e("Attempted to get flag with a null value for key. Returning fallback: %s", fallback);
            return EvaluationDetail.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND, fallback);  // no event is sent in this case
        }

        Flag flag = userManager.getCurrentUserFlagStore().getFlag(flagKey);
        JsonElement fallbackJson = fallback == null ? null : typeConverter.valueToJson(fallback);
        JsonElement valueJson = fallbackJson;
        EvaluationDetail<T> result;

        if (flag == null) {
            Timber.e("Attempted to get non-existent flag for key: %s Returning fallback: %s", flagKey, fallback);
            result = EvaluationDetail.error(EvaluationReason.ErrorKind.FLAG_NOT_FOUND, fallback);
        } else {
            valueJson = flag.getValue();
            if (valueJson == null || valueJson.isJsonNull()) {
                Timber.e("Attempted to get flag without value for key: %s Returning fallback: %s", flagKey, fallback);
                result = new EvaluationDetail<>(flag.getReason(), flag.getVariation(), fallback);
                valueJson = fallbackJson;
            } else {
                T value = typeConverter.valueFromJson(valueJson);
                if (value == null) {
                    Timber.e("Attempted to get flag with wrong type for key: %s Returning fallback: %s", flagKey, fallback);
                    result = EvaluationDetail.error(EvaluationReason.ErrorKind.WRONG_TYPE, fallback);
                    valueJson = fallbackJson;
                } else {
                    result = new EvaluationDetail<>(flag.getReason(), flag.getVariation(), value);
                }
            }
        }

        updateSummaryEvents(flagKey, flag, valueJson, fallbackJson);
        sendFlagRequestEvent(flagKey, flag, valueJson, fallbackJson, includeReasonInEvent ? result.getReason() : null);
        Timber.d("returning variation: %s flagKey: %s user key: %s", result, flagKey, userManager.getCurrentUser().getKeyAsString());
        return result;
    }

    /**
     * Closes the client. This should only be called at the end of a client's lifecycle.
     *
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        LDClient.closeInstances();
    }

    private void closeInternal() {
        updateProcessor.stop();
        eventProcessor.close();
        Application app = application.get();
        if (connectivityReceiver != null && app != null) {
            app.unregisterReceiver(connectivityReceiver);
            connectivityReceiver = null;
        }
        try {
            Foreground foreground = Foreground.get();
            if (foregroundListener != null) {
                foreground.removeListener(foregroundListener);
            }
        } catch (IllegalStateException ex) {
            // Foreground not initialized
        }
    }

    private static void closeInstances() throws IOException {
        for (LDClient client : instances.values()) {
            client.closeInternal();
        }
    }

    @Override
    public void flush() {
        LDClient.flushInstances();
    }

    private void flushInternal() {
        eventProcessor.flush();
    }

    private static void flushInstances() {
        for (LDClient client : instances.values()) {
            client.flushInternal();
        }
    }

    @Override
    public boolean isInitialized() {
        return isOffline() || updateProcessor.isInitialized();
    }

    @Override
    public boolean isOffline() {
        return isOffline;
    }

    @Override
    public synchronized void setOffline() {
        LDClient.setInstancesOffline();
    }

    private synchronized void setOfflineInternal() {
        Timber.d("Setting isOffline = true");
        throttler.cancel();
        isOffline = true;
        fetcher.setOffline();
        stopForegroundUpdating();
        eventProcessor.stop();
    }

    private synchronized static void setInstancesOffline() {
        for (LDClient client : instances.values()) {
            client.setOfflineInternal();
        }
    }

    @Override
    public synchronized void setOnline() {
        throttler.attemptRun();
    }

    private void setOnlineStatus() {
        LDClient.setOnlineStatusInstances();
    }

    private void setOnlineStatusInternal() {
        Timber.d("Setting isOffline = false");
        isOffline = false;
        fetcher.setOnline();
        if (isAppForegrounded) {
            startForegroundUpdating();
        } else {
            startBackgroundPolling();
        }
        eventProcessor.start();
    }

    private static void setOnlineStatusInstances() {
        for (LDClient client : instances.values()) {
            client.setOnlineStatusInternal();
        }
    }

    @Override
    public void registerFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.registerListener(flagKey, listener);
    }

    @Override
    public void unregisterFeatureFlagListener(String flagKey, FeatureFlagChangeListener listener) {
        userManager.unregisterListener(flagKey, listener);
    }

    @Override
    public boolean isDisableBackgroundPolling() {
        return config.isDisableBackgroundPolling();
    }

    @Override
    public String getVersion() {
        return BuildConfig.VERSION_NAME;
    }

    static String getInstanceId() {
        return instanceId;
    }

    void stopForegroundUpdating() {
        updateProcessor.stop();
    }

    void startForegroundUpdating() {
        if (!isOffline()) {
            updateProcessor.start();
        }
    }

    private void sendFlagRequestEvent(String flagKey, Flag flag, JsonElement value, JsonElement fallback, EvaluationReason reason) {
        if (flag == null) {
            return;
        }

        int version = flag.getVersionForEvents();
        Integer variation = flag.getVariation();
        if (flag.getTrackEvents()) {
            if (config.inlineUsersInEvents()) {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation, reason));
            } else {
                sendEvent(new FeatureRequestEvent(flagKey, userManager.getCurrentUser().getKeyAsString(), value, fallback, version, variation, reason));
            }
        } else {
            Long debugEventsUntilDate = flag.getDebugEventsUntilDate();
            if (debugEventsUntilDate != null) {
                long serverTimeMs = eventProcessor.getCurrentTimeMs();
                if (debugEventsUntilDate > System.currentTimeMillis() && debugEventsUntilDate > serverTimeMs) {
                    sendEvent(new DebugEvent(flagKey, userManager.getCurrentUser(), value, fallback, version, variation, reason));
                }
            }
        }
    }

    void startBackgroundPolling() {
        Application application = this.application.get();
        if (application != null && !config.isDisableBackgroundPolling() && isClientConnected(application, environmentName)) {
            PollingUpdater.startBackgroundPolling(application);
        }
    }

    private void sendEvent(Event event) {
        if (!isOffline()) {
            boolean processed = eventProcessor.sendEvent(event);
            if (!processed) {
                Timber.w("Exceeded event queue capacity. Increase capacity to avoid dropping events.");
            }
        }
    }

    /**
     * Updates the internal representation of a summary event, either adding a new field or updating the existing count.
     * Nothing is sent to the server.
     *
     * @param flagKey  The flagKey that will be updated
     * @param flag     The stored flag used in the evaluation of the flagKey
     * @param result   The value that was returned in the evaluation of the flagKey
     * @param fallback The fallback value used in the evaluation of the flagKey
     */
    private void updateSummaryEvents(String flagKey, Flag flag, JsonElement result, JsonElement fallback) {
        if (flag == null) {
            userManager.getSummaryEventSharedPreferences().addOrUpdateEvent(flagKey, result, fallback, -1, null);
        } else {
            int version = flag.getVersionForEvents();
            Integer variation = flag.getVariation();
            userManager.getSummaryEventSharedPreferences().addOrUpdateEvent(flagKey, result, fallback, version, variation);
        }
    }

    @VisibleForTesting
    public void clearSummaryEventSharedPreferences() {
        userManager.getSummaryEventSharedPreferences().clear();
    }

    @VisibleForTesting
    public SummaryEventSharedPreferences getSummaryEventSharedPreferences() {
        return userManager.getSummaryEventSharedPreferences();
    }

    UserManager getUserManager() {
        return userManager;
    }
}
