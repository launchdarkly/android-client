package com.launchdarkly.android;

import android.util.Pair;

import java.util.List;

/**
 * Listener interface for receiving FlagStore update callbacks
 */
interface StoreUpdatedListener {
    /**
     * Called by a FlagStore when the store is updated.
     *
     * @param updates Pairs of flag keys that were updated and the type of update that occurred.
     */
    void onStoreUpdate(List<Pair<String, FlagStoreUpdateType>> updates);
}
