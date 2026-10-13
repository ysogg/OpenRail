package dev.openrail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Geometry for rail paths. Points are rail block positions. */
final class PathMath {

    /** A rail block position. */
    record P(int x, int y, int z) {
        static P parse(String s) {
            String[] c = s.split(",");
            return new P(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    /** How far (blocks) a simplified line may stray from the rails. */
    static final double SIMPLIFY_TOLERANCE = 0.75;

    private PathMath() {
    }

    /**
     * Ramer-Douglas-Peucker simplification: the indices of the points kept, in order. Straight runs collapse to
     * their ends and staircase diagonals become one straight line; every kept point is a real rail block.
     */
    static int[] simplifyIndices(List<P> path) {
        if (path.size() <= 2) {
            int[] all = new int[path.size()];
            for (int i = 0; i < all.length; i++) {
                all[i] = i;
            }
            return all;
        }
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {0, path.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0];
            int end = range[1];
            double worst = -1;
            int worstIndex = -1;
            for (int i = start + 1; i < end; i++) {
                double d = distanceToSegment(path.get(i), path.get(start), path.get(end));
                if (d > worst) {
                    worst = d;
                    worstIndex = i;
                }
            }
            if (worst > SIMPLIFY_TOLERANCE) {
                keep[worstIndex] = true;
                stack.push(new int[] {start, worstIndex});
                stack.push(new int[] {worstIndex, end});
            }
        }
        int count = 0;
        for (boolean k : keep) {
            if (k) {
                count++;
            }
        }
        int[] out = new int[count];
        int n = 0;
        for (int i = 0; i < path.size(); i++) {
            if (keep[i]) {
                out[n++] = i;
            }
        }
        return out;
    }

    /**
     * Effective travel distance split by the copper under the track. A minecart's speed is capped per horizontal
     * axis, so a diagonal piece takes as long as its longer side (the Chebyshev distance).
     * {@code tiers.get(i)} is the copper under rail block i (null = none). Each simplified piece's
     * distance is shared among its blocks in proportion to their copper.
     */
    static double[] travelByCopper(List<P> path, List<OxidationState> tiers, int[] kept) {
        double[] out = new double[TrackEdge.TIERS];
        for (int k = 1; k < kept.length; k++) {
            int from = kept[k - 1];
            int to = kept[k];
            P a = path.get(from);
            P b = path.get(to);
            double travel = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.z() - a.z()));
            int blocks = to - from;
            for (int i = from; i < to; i++) {
                OxidationState t = i < tiers.size() ? tiers.get(i) : null;
                out[t == null ? 0 : t.ordinal() + 1] += travel / blocks;
            }
        }
        return out;
    }

    static double distanceToSegment(P p, P a, P b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double abz = b.z() - a.z();
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 == 0 ? 0 : ((p.x() - a.x()) * abx + (p.y() - a.y()) * aby + (p.z() - a.z()) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.x() + t * abx - p.x();
        double dy = a.y() + t * aby - p.y();
        double dz = a.z() + t * abz - p.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double distanceToSegment(double px, double py, double pz, P a, P b) {
        double abx = b.x() - a.x();
        double aby = b.y() - a.y();
        double abz = b.z() - a.z();
        double len2 = abx * abx + aby * aby + abz * abz;
        double t = len2 == 0 ? 0 : ((px - a.x()) * abx + (py - a.y()) * aby + (pz - a.z()) * abz) / len2;
        t = Math.max(0, Math.min(1, t));
        double dx = a.x() + t * abx - px;
        double dy = a.y() + t * aby - py;
        double dz = a.z() + t * abz - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double distanceToLine(double px, double py, double pz, List<P> line) {
        if (line.size() == 1) {
            P a = line.getFirst();
            return Math.sqrt(Math.pow(a.x() - px, 2) + Math.pow(a.y() - py, 2) + Math.pow(a.z() - pz, 2));
        }
        double best = Double.MAX_VALUE;
        for (int i = 1; i < line.size(); i++) {
            best = Math.min(best, distanceToSegment(px, py, pz, line.get(i - 1), line.get(i)));
        }
        return best;
    }

}
