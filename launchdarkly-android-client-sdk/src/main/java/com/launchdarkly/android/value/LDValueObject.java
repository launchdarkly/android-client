package com.launchdarkly.android.value;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@JsonAdapter(LDValueTypeAdapter.class)
final class LDValueObject extends LDValue {
    private static final LDValueObject EMPTY = new LDValueObject(new HashMap<String, LDValue>());
    private final Map<String, LDValue> map;

    static LDValueObject fromMap(Map<String, LDValue> map) {
        return map.isEmpty() ? EMPTY : new LDValueObject(map);
    }

    private LDValueObject(Map<String, LDValue> map) {
        this.map = map;
    }

    public LDValueType getType() {
        return LDValueType.OBJECT;
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Iterable<String> keys() {
        return map.keySet();
    }

    @Override
    public Iterable<LDValue> values() {
        return map.values();
    }

    @Override
    public LDValue get(String name) {
        LDValue v = map.get(name);
        return v == null ? ofNull() : v;
    }

    @Override
    void write(JsonWriter writer) throws IOException {
        writer.beginObject();
        for (Map.Entry<String, LDValue> e : map.entrySet()) {
            writer.name(e.getKey());
            e.getValue().write(writer);
        }
        writer.endObject();
    }

    @Override
    @SuppressWarnings("deprecation")
    JsonElement computeJsonElement() {
        JsonObject o = new JsonObject();
        for (String key : map.keySet()) {
            o.add(key, map.get(key).asUnsafeJsonElement());
        }
        return o;
    }
}
