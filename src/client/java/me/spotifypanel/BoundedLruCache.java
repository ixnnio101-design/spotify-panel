package me.spotifypanel;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/** Thread-safe access-ordered cache with a strict approximate memory-weight limit. */
final class BoundedLruCache<K, V> {
    private final long maxWeight;
    private final ToLongFunction<V> weigher;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(32, 0.75f, true);
    private long currentWeight;

    BoundedLruCache(long maxWeight, ToLongFunction<V> weigher) {
        if (maxWeight <= 0) throw new IllegalArgumentException("maxWeight must be positive");
        this.maxWeight = maxWeight;
        this.weigher = weigher;
    }

    synchronized V get(K key) {
        return entries.get(key);
    }

    synchronized V putIfAbsent(K key, V value) {
        V existing = entries.get(key);
        if (existing != null) return existing;
        putInternal(key, value);
        return null;
    }

    synchronized V put(K key, V value) {
        V previous = entries.get(key);
        putInternal(key, value);
        return previous;
    }

    private void putInternal(K key, V value) {
        if (key == null || value == null) return;
        long weight = Math.max(1L, weigher.applyAsLong(value));
        V previous = entries.remove(key);
        if (previous != null) currentWeight -= weightOf(previous);
        if (weight > maxWeight) return;

        entries.put(key, value);
        currentWeight += weight;
        var iterator = entries.entrySet().iterator();
        while (currentWeight > maxWeight && iterator.hasNext()) {
            Map.Entry<K, V> eldest = iterator.next();
            currentWeight -= weightOf(eldest.getValue());
            iterator.remove();
        }
    }

    synchronized boolean containsKey(K key) {
        return entries.containsKey(key);
    }

    synchronized V remove(K key) {
        V removed = entries.remove(key);
        if (removed != null) currentWeight -= weightOf(removed);
        return removed;
    }

    synchronized void clear() {
        entries.clear();
        currentWeight = 0L;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long currentWeight() {
        return currentWeight;
    }

    private long weightOf(V value) {
        return Math.max(1L, weigher.applyAsLong(value));
    }
}
