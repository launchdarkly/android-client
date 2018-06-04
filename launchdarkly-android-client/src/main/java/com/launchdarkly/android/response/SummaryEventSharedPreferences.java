package com.launchdarkly.android.response;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Created by jamesthacker on 4/12/18.
 */

public interface SummaryEventSharedPreferences {

    void clear();

    void addOrUpdateEvent(String flagResponseKey, JsonElement value, JsonElement defaultVal, int version, int variation, boolean unknown);

    JsonObject getFeaturesJsonObject();
}
