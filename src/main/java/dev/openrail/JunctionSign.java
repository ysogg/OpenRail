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
 * A {@code [junction]} sign, placed directly above a junction rail. Line 1 is {@code [junction]};
 * line 2 is an optional label shown on the map (it isn't a destination).
 */
public record JunctionSign(String label) {

    public static final String HEADER = "[junction]";

    public static boolean isHeader(String line) {
        return DestinationStore.normalize(line).equals(HEADER);
    }

    public static Optional<JunctionSign> parse(List<String> lines) {
        if (lines.isEmpty() || !isHeader(lines.getFirst())) {
            return Optional.empty();
        }
        return Optional.of(new JunctionSign(lines.size() > 1 ? lines.get(1).trim() : ""));
    }

    public static Optional<JunctionSign> parseComponents(List<Component> lines) {
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        return parse(lines.stream().map(plain::serialize).toList());
    }

    /** Reads a junction sign from a block, front side first, then back. */
    public static Optional<JunctionSign> read(Block block) {
        BlockState state = block.getState(false);
        if (!(state instanceof Sign sign)) {
            return Optional.empty();
        }
        Optional<JunctionSign> front = parseComponents(sign.getSide(Side.FRONT).lines());
        return front.isPresent() ? front : parseComponents(sign.getSide(Side.BACK).lines());
    }
}
