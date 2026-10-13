package dev.openrail;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;

/**
 * Copper oxidation stages. Each covers the copper block and copper grate, waxed or not.
 *
 * <p>Blocks are identified by their vanilla block ID (e.g. {@code exposed_copper}) rather than by
 * {@link Material} constants, so the plugin doesn't depend on how a particular server build names or
 * remaps its Java constants.
 */
public enum OxidationState {
    UNOXIDIZED(2.0, "copper_block", "copper_grate", "cut_copper", "waxed_copper_block", "waxed_copper_grate", "waxed_cut_copper"),
    EXPOSED(1.7, "exposed_copper", "exposed_copper_grate", "exposed_cut_copper", "waxed_exposed_copper", "waxed_exposed_copper_grate", "waxed_exposed_cut_copper"),
    WEATHERED(1.3, "weathered_copper", "weathered_copper_grate", "weathered_cut_copper", "waxed_weathered_copper", "waxed_weathered_copper_grate", "waxed_weathered_cut_copper"),
    OXIDIZED(1.1, "oxidized_copper", "oxidized_copper_grate", "oxidized_cut_copper", "waxed_oxidized_copper", "waxed_oxidized_copper_grate", "waxed_oxidized_cut_copper");

    private static final Map<String, OxidationState> BY_ID = new HashMap<>();

    static {
        for (OxidationState state : values()) {
            for (String id : state.blockIds) {
                BY_ID.put(id, state);
            }
        }
    }

    private final double defaultMultiplier;
    private final List<String> blockIds;

    OxidationState(double defaultMultiplier, String... blockIds) {
        this.defaultMultiplier = defaultMultiplier;
        this.blockIds = List.of(blockIds);
    }

    public double defaultMultiplier() {
        return defaultMultiplier;
    }

    /** Vanilla block IDs (without the {@code minecraft:} namespace) in this stage. */
    public List<String> blockIds() {
        return blockIds;
    }

    /** Name used in commands and config.yml, e.g. {@code exposed}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The oxidation state a copper block or grate is in, or null if it isn't one. */
    public static OxidationState of(Material block) {
        return BY_ID.get(blockId(block));
    }

    /** A material's vanilla ID without namespace, or "" if it has none. */
    static String blockId(Material material) {
        try {
            return material.getKey().getKey();
        } catch (RuntimeException e) { // legacy materials have no key
            return "";
        }
    }

    /** Parses a state name; null if unknown. */
    public static OxidationState parse(String name) {
        for (OxidationState state : values()) {
            if (state.key().equalsIgnoreCase(name)) {
                return state;
            }
        }
        return null;
    }
}
