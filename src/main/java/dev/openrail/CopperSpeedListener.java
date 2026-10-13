package dev.openrail;

import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Copper under the track raises the top speed of player-carrying minecarts. It never speeds a cart
 * up by itself: powered rails (or slopes) still have to do the accelerating.
 * Carts this listener has modified are tagged so they can be returned to normal afterwards,
 * without touching carts whose speed another plugin manages.
 */
public final class CopperSpeedListener implements Listener {

    private static final double EPSILON = 1e-6;
    /** Fraction of its velocity a ridden minecart moves per tick in vanilla. */
    private static final double RIDDEN_MOVE_FACTOR = 0.75;

    private final Plugin plugin;
    private final CopperSpeedSettings settings;
    private final NamespacedKey boostedKey;

    public CopperSpeedListener(Plugin plugin, CopperSpeedSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.boostedKey = new NamespacedKey(plugin, "copper_boosted");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart)) {
            return;
        }
        if (!settings.enabled() || !hasPlayer(cart)) {
            if (isBoosted(cart)) {
                reset(cart);
            }
            return;
        }
        Block rail = RailUtil.railAt(event.getTo());
        if (rail == null) {
            return; // airborne or derailed: keep the current speed rather than jolting it
        }
        double multiplier = multiplierUnder(rail);
        if (multiplier == 1.0 && !isBoosted(cart)) {
            return; // ordinary track, untouched cart: leave it to vanilla / other plugins
        }
        apply(cart, settings.baseMaxSpeed() * multiplier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !isBoosted(cart)) {
            return;
        }
        // Passengers are only updated after the event, so check on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (cart.isValid() && !hasPlayer(cart)) {
                reset(cart);
            }
        });
    }

    /** Highest multiplier among the blocks 1..searchDepth beneath the rail, or 1.0. */
    double multiplierUnder(Block rail) {
        double best = 1.0;
        Block below = rail;
        for (int i = 0; i < settings.searchDepth(); i++) {
            below = below.getRelative(BlockFace.DOWN);
            Double m = settings.multiplierFor(below.getType());
            if (m != null && (best == 1.0 || m > best)) {
                best = m;
            }
        }
        return best;
    }

    private void apply(Minecart cart, double targetMaxSpeed) {
        double current = cart.getMaxSpeed();
        if (Math.abs(current - targetMaxSpeed) < EPSILON) {
            return;
        }
        if (targetMaxSpeed > current) {
            trimHiddenMomentum(cart, current);
        }
        cart.setMaxSpeed(targetMaxSpeed);
        if (Math.abs(targetMaxSpeed - settings.baseMaxSpeed()) < EPSILON) {
            cart.getPersistentDataContainer().remove(boostedKey);
        } else {
            cart.getPersistentDataContainer().set(boostedKey, PersistentDataType.BOOLEAN, true);
        }
    }

    /**
     * Vanilla only caps how far a cart moves each tick (0.75 x velocity for a ridden cart, clamped to
     * the max speed); the velocity itself can keep building up to 2.0 on powered rails. Raising the cap
     * would release that stored momentum as an instant speed-up, so first trim the velocity to what
     * the cart was actually showing under the old cap. Powered rails then do the accelerating.
     */
    private static void trimHiddenMomentum(Minecart cart, double oldMaxSpeed) {
        Vector v = cart.getVelocity();
        double horizontal = Math.hypot(v.getX(), v.getZ());
        double limit = oldMaxSpeed / RIDDEN_MOVE_FACTOR;
        if (horizontal > limit) {
            double k = limit / horizontal;
            cart.setVelocity(new Vector(v.getX() * k, v.getY(), v.getZ() * k));
        }
    }

    private void reset(Minecart cart) {
        cart.setMaxSpeed(settings.baseMaxSpeed());
        cart.getPersistentDataContainer().remove(boostedKey);
    }

    private boolean isBoosted(Minecart cart) {
        return cart.getPersistentDataContainer().has(boostedKey, PersistentDataType.BOOLEAN);
    }

    private static boolean hasPlayer(Minecart cart) {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }
        return false;
    }
}
