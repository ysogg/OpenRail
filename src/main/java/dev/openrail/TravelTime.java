package dev.openrail;

/**
 * Travel-time estimates for a stretch of track, assuming the cart runs at top speed throughout.
 * The fast figure assumes every rail is over fresh (unoxidized) copper; the slow one assumes no copper.
 */
final class TravelTime {

    private TravelTime() {
    }

    /** "Est. Time: 1:18-2:35", or just the no-copper time when copper rails are off. */
    static String estimate(double travelBlocks, CopperSpeedSettings copper) {
        double normalSpeed = copper.baseMaxSpeed() * 20; // blocks per second
        double slow = travelBlocks / normalSpeed;
        double multiplier = copper.multiplier(OxidationState.UNOXIDIZED);
        if (!copper.enabled() || multiplier <= 1.0) {
            return "Est. Time: " + format(slow);
        }
        double fast = slow / multiplier;
        return "Est. Time: " + format(fast) + "-" + format(slow);
    }

    /** m:ss, or h:mm:ss for an hour or more. */
    static String format(double seconds) {
        long total = Math.round(seconds);
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
