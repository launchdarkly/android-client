package com.launchdarkly.sdk.android;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.launchdarkly.sdk.android.DiagnosticId;
import com.launchdarkly.sdk.android.GsonCache;

import org.junit.Test;

import java.util.UUID;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

public class DiagnosticIdTest {

    @Test
    public void hasUUID() {
        UUID uuid = UUID.randomUUID();
        DiagnosticId diagnosticId = new DiagnosticId(uuid.toString(), "SDK_KEY");
        assertNotNull(diagnosticId.diagnosticId);
        assertEquals(uuid.toString(), diagnosticId.diagnosticId);
    }

    @Test
    public void nullKeyIsSafe() {
        // We can't send diagnostics without a key anyway, so we're just validating that the
        // constructor won't crash with a null key
        new DiagnosticId(UUID.randomUUID().toString(), null);
    }

    @Test
    public void shortKeyIsSafe() {
        DiagnosticId diagnosticId = new DiagnosticId(UUID.randomUUID().toString(), "foo");
        assertEquals("foo", diagnosticId.sdkKeySuffix);
    }

    @Test
    public void keyIsSuffix() {
        DiagnosticId diagnosticId = new DiagnosticId(UUID.randomUUID().toString(), "this_is_a_fake_key");
        assertEquals("ke_key", diagnosticId.sdkKeySuffix);
    }

    @Test
    public void gsonSerialization() {
        Gson gson = GsonCache.getGson();
        UUID uuid = UUID.randomUUID();
        DiagnosticId diagnosticId = new DiagnosticId(uuid.toString(), "this_is_a_fake_key");
        JsonObject jsonObject = gson.toJsonTree(diagnosticId).getAsJsonObject();
        assertEquals(2, jsonObject.size());
        String id = jsonObject.getAsJsonPrimitive("diagnosticId").getAsString();
        assertEquals(uuid.toString(), id);
        assertEquals("ke_key", jsonObject.getAsJsonPrimitive("sdkKeySuffix").getAsString());
    }
}
