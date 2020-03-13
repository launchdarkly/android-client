package com.launchdarkly.android;

import android.support.annotation.IntRange;
import android.support.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.launchdarkly.android.value.LDValue;

import timber.log.Timber;

class Event {
    @Expose
    String kind;

    Event(String kind) {
        this.kind = kind;
    }
}

class GenericEvent extends Event {
    @Expose
    Long creationDate;
    @Expose
    String key;
    @Expose
    LDUser user;
    @Expose
    String userKey;

    GenericEvent(String kind, String key, LDUser user) {
        super(kind);
        this.creationDate = System.currentTimeMillis();
        this.key = key;
        this.user = user;
    }
}

class IdentifyEvent extends GenericEvent {

    IdentifyEvent(LDUser user) {
        super("identify", user.getKey(), user);
    }
}

class CustomEvent extends GenericEvent {
    @Expose
    final LDValue data;
    @Expose
    final Double metricValue;

    CustomEvent(String key, LDUser user, LDValue data, Double metricValue) {
        super("custom", key, user);
        this.data = data;
        this.metricValue = metricValue;
    }

    CustomEvent(String key, String userKey, LDValue data, Double metricValue) {
        super("custom", key, null);
        this.data = data;
        this.userKey = userKey;
        this.metricValue = metricValue;
    }
}

class FeatureRequestEvent extends GenericEvent {
    @Expose
    LDValue value;

    @Expose
    @SerializedName("default")
    LDValue defaultVal;

    @Expose
    Integer version;

    @Expose
    Integer variation;

    @Expose
    EvaluationReason reason;

    /**
     * Creates a FeatureRequestEvent which includes the full user object.
     *
     * @param key        The feature flag key
     * @param user       The full user object
     * @param value      The value of the feature flag
     * @param defaultVal The default value of the feature flag
     * @param version    The stored version of the feature flag
     * @param variation  The stored variation of the feature flag
     */
    FeatureRequestEvent(String key, LDUser user, LDValue value, LDValue defaultVal,
                        @IntRange(from=(0), to=(Integer.MAX_VALUE)) int version,
                        @Nullable Integer variation,
                        @Nullable EvaluationReason reason) {
        super("feature", key, user);
        this.value = value;
        this.defaultVal = defaultVal;
        setOptionalValues(version, variation, reason);
    }


    /**
     * Creates a FeatureRequestEvent which includes only the userKey.  User will be null.
     *
     * @param key        The feature flag key
     * @param userKey    The user key
     * @param value      The value of the feature flag
     * @param defaultVal The default value of the feature flag
     * @param version    The stored version of the feature flag
     * @param variation  The stored variation of the feature flag
     */
    FeatureRequestEvent(String key, String userKey, LDValue value, LDValue defaultVal,
                        @IntRange(from=(0), to=(Integer.MAX_VALUE)) int version,
                        @Nullable Integer variation,
                        @Nullable EvaluationReason reason) {
        super("feature", key, null);
        this.value = value;
        this.defaultVal = defaultVal;
        this.userKey = userKey;
        setOptionalValues(version, variation, reason);
    }

    private void setOptionalValues(int version, @Nullable Integer variation, @Nullable EvaluationReason reason) {
        if (version != -1) {
            this.version = version;
        } else {
            Timber.d("Feature Event: Ignoring version for flag: %s", key);
        }

        if (variation != null) {
            this.variation = variation;
        } else {
            Timber.d("Feature Event: Ignoring variation for flag: %s", key);
        }

        this.reason = reason;
    }
}

class DebugEvent extends FeatureRequestEvent {

    DebugEvent(String key, LDUser user, LDValue value, LDValue defaultVal, @IntRange(from=(0), to=(Integer.MAX_VALUE)) int version, @Nullable Integer variation, @Nullable EvaluationReason reason) {
        super(key, user, value, defaultVal, version, variation, reason);
        this.kind = "debug";
    }
}

class SummaryEvent extends Event {

    @Expose
    @SerializedName("startDate")
    Long startDate;

    @Expose
    @SerializedName("endDate")
    Long endDate;

    @Expose
    @SerializedName("features")
    JsonObject features;

    SummaryEvent(Long startDate, Long endDate, JsonObject features) {
        super("summary");
        this.startDate = startDate;
        this.endDate = endDate;
        this.features = features;
    }

    @Override
    public String toString() {
        JsonObject jsonObject = new JsonObject();
        if (startDate != null) {
            jsonObject.add("startDate", new JsonPrimitive(startDate));
        }
        if (endDate != null) {
            jsonObject.add("endDate", new JsonPrimitive(endDate));
        }
        if (kind != null) {
            jsonObject.add("kind", new JsonPrimitive(kind));
        }
        jsonObject.add("features", features);
        return jsonObject.toString();
    }
}
