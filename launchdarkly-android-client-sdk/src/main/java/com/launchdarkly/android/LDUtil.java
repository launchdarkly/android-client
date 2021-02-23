package com.launchdarkly.android;

import com.launchdarkly.sdk.LDUser;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.UserAttribute;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import android.os.Build;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.stream.StreamSupport;
import java.util.stream.Stream;

import java.security.GeneralSecurityException;
import okhttp3.OkHttpClient;

import java.io.IOException;

class LDUtil {

    /**
     * Looks at the Android device status to determine if the device is online.
     *
     * @param context Context for getting the ConnectivityManager
     * @return whether device is connected to the internet
     */
    @SuppressWarnings("deprecation")
    static boolean isInternetConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        // TODO: at the point our min version is >= 23 we can remove the old compat code
        if (Build.VERSION.SDK_INT >= 23) {
            Network net = cm.getActiveNetwork();
            if (net == null)
                return false;

            NetworkCapabilities nwc = cm.getNetworkCapabilities(net);

            // the older solution was cleaner but android went and
            // deprecated it :^)
            // hasTransport(NET_CAPABILITY_INTERNET) always returns false on emulators
            // so we check these instead
            return nwc != null && (
                nwc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                || nwc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
            );
        } else {
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnectedOrConnecting();
        }
    }

    /**
     * Looks at both the Android device status and the environment's {@link LDClient} to determine if any network calls should be made.
     *
     * @param context         Context for getting the ConnectivityManager
     * @param environmentName Name of the environment to get the LDClient for
     * @return whether the device is connected to the internet and the LDClient instance is online
     */
    static boolean isClientConnected(Context context, String environmentName) {
        boolean deviceConnected = isInternetConnected(context);
        try {
            return deviceConnected && !LDClient.getForMobileKey(environmentName).isOffline();
        } catch (LaunchDarklyException e) {
            LDConfig.LOG.e(e, "Exception caught when getting LDClient");
            return false;
        }
    }

    @NonNull
    static <T> Map<String, T> sharedPrefsGetAllGson(SharedPreferences sharedPreferences, Class<T> typeOf) {
        Map<String, ?> flags = sharedPreferences.getAll();
        Map<String, T> deserialized = new HashMap<>();
        for (Map.Entry<String, ?> entry : flags.entrySet()) {
            if (entry.getValue() instanceof String) {
                try {
                    T obj = GsonCache.getGson().fromJson((String) entry.getValue(), typeOf);
                    deserialized.put(entry.getKey(), obj);
                } catch (Exception ignored) {
                }
            }
        }
        return deserialized;
    }

    static void setupSocketFactory(OkHttpClient.Builder builder) {
        if (Build.VERSION.SDK_INT < 22) {
            try {
                builder.sslSocketFactory(new ModernTLSSocketFactory(), TLSUtils.defaultTrustManager());
            } catch (GeneralSecurityException ignored) {
                // TLS is not available, so don't set up the socket factory, swallow the exception
            }
        }
    }

    static <T> T sharedPrefsGetGson(SharedPreferences sharedPreferences, Class<T> typeOf, String key) {
        String data = sharedPreferences.getString(key, null);
        if (data == null) return null;
        try {
            return GsonCache.getGson().fromJson(data, typeOf);
        } catch (Exception ignored) {
            return null;
        }
    }

    interface ResultCallback<T> {
        void onSuccess(T result);
        void onError(Throwable e);
    }

    /**
     * Tests whether an HTTP error status represents a condition that might resolve on its own if we retry.
     * @param statusCode the HTTP status
     * @return true if retrying makes sense; false if it should be considered a permanent failure
     */
    static boolean isHttpErrorRecoverable(int statusCode) {
        if (statusCode >= 400 && statusCode < 500) {
            switch (statusCode) {
                case 400: // bad request
                case 408: // request timeout
                case 429: // too many requests
                    return true;
                default:
                    return false; // all other 4xx errors are unrecoverable
            }
        }
        return true;
    }

    static class LDUserPrivateAttributesTypeAdapter extends TypeAdapter<LDUser> {
        private final LDConfig config;

        LDUserPrivateAttributesTypeAdapter(LDConfig cfg) {
            config = cfg;
        }

        private static final UserAttribute DEVICE = UserAttribute.forName("device");
        private static final UserAttribute OS = UserAttribute.forName("os");

        private boolean isPrivate(LDUser user, UserAttribute key) {
            if (config.allAttributesPrivate()) {
                return true;
            }

            // "device" and "os" are always private
            if (key.equals(DEVICE) || key.equals(OS)) {
                return true;
            }

            // the config can also a list of private attributes
            for (UserAttribute it : config.getPrivateAttributes()) {
                if (it.equals(key)) {
                    return true;
                }
            }

            // the user has a list of private attributes as well
            for (UserAttribute it : user.getPrivateAttributes()) {
                if (it.equals(key)) {
                    return true;
                }
            }

            // if those checks failed then this attribute isnt private
            return false;
        }

        private void safeWrite(
            JsonWriter out, LDUser user,
            UserAttribute attrib,
            Set<String> attrs) throws IOException {

            LDValue value = user.getAttribute(attrib);

            // skip null attributes
            if (value.isNull()) {
                return;
            }

            if (isPrivate(user, attrib)) {
                attrs.add(attrib.getName());
            } else {
                out.name(attrib.getName()).value(value.stringValue());
            }
        }

        private void writeAttribs(JsonWriter out, LDUser user, Set<String> names) throws IOException {
            boolean started = false;

            for (UserAttribute entry : user.getCustomAttributes()) {
                if (isPrivate(user, entry)) {
                    names.add(entry.getName());
                } else {
                    if (!started) {
                        out.name("custom");
                        out.beginObject();
                        started = true;
                    }
                    out.name(entry.getName());
                    LDConfig.GSON.getAdapter(LDValue.class).write(out, user.getAttribute(entry));
                }
            }

            if (started) {
                out.endObject();
            }
        }

        private void writePrivateAttribs(JsonWriter out, Set<String> attrs) throws IOException {
            if (attrs.isEmpty()) 
                return;

            out.name("privateAttrs");
            out.beginArray();

            for (String name : attrs)
                out.value(name);

            out.endArray();
        }

        private static final UserAttribute[] OPTIONAL_BUILTINS = {
            UserAttribute.SECONDARY_KEY,
            UserAttribute.IP,
            UserAttribute.EMAIL,
            UserAttribute.NAME,
            UserAttribute.AVATAR,
            UserAttribute.FIRST_NAME,
            UserAttribute.LAST_NAME,
            UserAttribute.COUNTRY
        };

        @Override
        public void write(JsonWriter out, LDUser user) throws IOException {
            if (user == null) {
                out.nullValue();
                return;
            }

            Set<String> privateAttrs = new HashSet<>();

            out.beginObject();

            out.name("key").value(user.getKey());
            out.name("anonymous").value(user.isAnonymous());

            for (UserAttribute attrib : OPTIONAL_BUILTINS) {
                safeWrite(out, user, attrib, privateAttrs);
            }

            writeAttribs(out, user, privateAttrs);
            writePrivateAttribs(out, privateAttrs);

            out.endObject();
        }

        @Override
        public LDUser read(JsonReader in) throws IOException {
            return LDConfig.GSON.fromJson(in, LDUser.class);
        }
    }
}
