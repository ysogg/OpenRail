package dev.openrail;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

/**
 * The {@code copper-speed} section of config.yml. Mutable so operators can change it in game;
 * {@link #writeTo} puts the current values back into the config for saving.
 */
public final class CopperSpeedSettings {

    /** Above this, vanilla's own velocity limit stops carts from ever reaching the top speed. */
    public static final double MAX_MULTIPLIER = 3.75;
    /** Above this, carts move a block or more per tick and start skipping curves. */
    public static final double SAFE_MULTIPLIER = 2.5;

    private boolean enabled = true;
    private double baseMaxSpeed = 0.4;
    private int searchDepth = 2;
    private final Map<OxidationState, Double> multipliers = new EnumMap<>(OxidationState.class);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public CopperSpeedSettings() {
        for (OxidationState state : OxidationState.values()) {
            setMultiplier(state, state.defaultMultiplier());
        }
    }

    public static CopperSpeedSettings load(ConfigurationSection section, Logger log) {
        CopperSpeedSettings s = new CopperSpeedSettings();
        if (section == null) {
            return s;
        }
        s.enabled = section.getBoolean("enabled", true);
        s.baseMaxSpeed = Math.max(0.01, section.getDouble("base-max-speed", 0.4));
        s.searchDepth = Math.max(1, section.getInt("search-depth", 2));
        for (OxidationState state : OxidationState.values()) {
            double value = section.getDouble("multipliers." + state.key(), state.defaultMultiplier());
            if (!isValidMultiplier(value)) {
                log.warning("copper-speed.multipliers." + state.key() + " must be above 0 and at most "
                    + MAX_MULTIPLIER + "; using " + state.defaultMultiplier());
                value = state.defaultMultiplier();
            }
            s.setMultiplier(state, value);
        }
        return s;
    }

    public void writeTo(ConfigurationSection root) {
        root.set("copper-speed.enabled", enabled);
        for (OxidationState state : OxidationState.values()) {
            root.set("copper-speed.multipliers." + state.key(), multipliers.get(state));
        }
    }

    public static boolean isValidMultiplier(double value) {
        return value > 0 && value <= MAX_MULTIPLIER && !Double.isNaN(value);
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        listeners.forEach(Runnable::run);
    }

    /** Called after an operator changes a setting in game (e.g. to refresh travel-time estimates). */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public double baseMaxSpeed() {
        return baseMaxSpeed;
    }

    public int searchDepth() {
        return searchDepth;
    }

    public double multiplier(OxidationState state) {
        return multipliers.get(state);
    }

    public void setMultiplier(OxidationState state, double value) {
        multipliers.put(state, value);
        listeners.forEach(Runnable::run);
    }

    /**
     * The copper that sets the speed on a rail: of the blocks 1..searchDepth beneath it, the one with the
     * highest multiplier. Null if there's none.
     */
    public OxidationState copperUnder(org.bukkit.block.Block rail) {
        OxidationState best = null;
        org.bukkit.block.Block below = rail;
        for (int i = 0; i < searchDepth; i++) {
            below = below.getRelative(org.bukkit.block.BlockFace.DOWN);
            OxidationState state = OxidationState.of(below.getType());
            if (state != null && (best == null || multiplier(state) > multiplier(best))) {
                best = state;
            }
        }
        return best;
    }

    /** Multiplier for a block, or null if it isn't a speed-affecting copper block. */
    public Double multiplierFor(Material block) {
        OxidationState state = OxidationState.of(block);
        return state == null ? null : multipliers.get(state);
    }
}
