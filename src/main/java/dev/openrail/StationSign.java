package dev.openrail;

import java.util.List;
import java.util.Optional;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;

/**
 * A parsed {@code [station]} sign, placed at a platform to label a real station.
 *
 * <pre>
 * Line 1: [station]      (case-insensitive)
 * Line 2: station name   (one word: it's what players type after /dest)
 * Line 3-4: optional description
 * </pre>
 */
public record StationSign(String name, String description) {

    public static final String HEADER = "[station]";

    /** Why a [station] sign is unusable, or null if it's fine. */
    public enum Problem {
        MISSING_NAME("The second line must be the station name."),
        NAME_HAS_SPACES("Station names can't contain spaces, since players type them after /dest. Try - or _ instead."),
        RESERVED_NAME("That name is reserved; pick another.");

        public final String message;

        Problem(String message) {
            this.message = message;
        }
    }

    public String key() {
        return DestinationStore.normalize(name);
    }

    public static boolean isHeader(String line) {
        return DestinationStore.normalize(line).equals(HEADER);
    }

    /** Checks the name line of a sign whose header is [station]. */
    public static Problem validate(String nameLine) {
        String name = nameLine.trim();
        if (name.isEmpty()) {
            return Problem.MISSING_NAME;
        }
        if (name.chars().anyMatch(Character::isWhitespace)) {
            return Problem.NAME_HAS_SPACES;
        }
        if (name.equals("*") || name.startsWith("[")) {
            return Problem.RESERVED_NAME;
        }
        return null;
    }

    /** Parses plain-text sign lines. Empty unless it's a valid station sign. */
    public static Optional<StationSign> parse(List<String> lines) {
        if (lines.size() < 2 || !isHeader(lines.getFirst()) || validate(lines.get(1)) != null) {
            return Optional.empty();
        }
        StringBuilder description = new StringBuilder();
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.isEmpty()) {
                if (!description.isEmpty()) {
                    description.append(' ');
                }
                description.append(line);
            }
        }
        return Optional.of(new StationSign(lines.get(1).trim(), description.toString()));
    }

    public static Optional<StationSign> parseComponents(List<Component> lines) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        return parse(lines.stream().map(plain::serialize).toList());
    }

    /** Reads a station sign from a block, checking the front side first and then the back. */
    public static Optional<StationSign> read(Block block) {
        BlockState state = block.getState(false);
        if (!(state instanceof Sign sign)) {
            return Optional.empty();
        }
        Optional<StationSign> front = parseComponents(sign.getSide(Side.FRONT).lines());
        return front.isPresent() ? front : parseComponents(sign.getSide(Side.BACK).lines());
    }
}
