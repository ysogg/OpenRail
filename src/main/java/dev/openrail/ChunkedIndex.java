package dev.openrail;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Values keyed by block, with a per-chunk index so a chunk's entries can be rescanned. */
final class ChunkedIndex<V> {

    private final Map<BlockKey, V> byBlock = new HashMap<>();
    private final Map<BlockKey.ChunkKey, Set<BlockKey>> byChunk = new HashMap<>();

    V get(BlockKey key) {
        return byBlock.get(key);
    }

    /** Stores a value; returns the previous one (null if none). */
    V put(BlockKey key, V value) {
        V old = byBlock.put(key, value);
        byChunk.computeIfAbsent(key.chunk(), k -> new HashSet<>()).add(key);
        return old;
    }

    V remove(BlockKey key) {
        V old = byBlock.remove(key);
        if (old != null) {
            Set<BlockKey> inChunk = byChunk.get(key.chunk());
            if (inChunk != null) {
                inChunk.remove(key);
                if (inChunk.isEmpty()) {
                    byChunk.remove(key.chunk());
                }
            }
        }
        return old;
    }

    Set<BlockKey> inChunk(BlockKey.ChunkKey chunk) {
        Set<BlockKey> keys = byChunk.get(chunk);
        return keys == null ? Set.of() : Set.copyOf(keys);
    }

    Map<BlockKey, V> view() {
        return Collections.unmodifiableMap(byBlock);
    }
}
