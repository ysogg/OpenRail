package dev.openrail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Operator commands for copper rails. Changes take effect immediately and are saved to config.yml.
 * <ul>
 *   <li>{@code /copperrails [true|false]}: turn the copper speed effect on or off (no argument shows the state)</li>
 *   <li>{@code /coppermultiplier [state] [multiplier]}: set a stage's top-speed multiplier (fewer arguments show values)</li>
 * </ul>
 */
public final class CopperCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final CopperSpeedSettings settings;

    public CopperCommand(JavaPlugin plugin, CopperSpeedSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "copperrails" -> toggle(sender, args);
            case "coppermultiplier" -> multiplier(sender, args);
            default -> false;
        };
    }

    private boolean toggle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Copper rails are " + (settings.enabled() ? "enabled." : "disabled."),
                NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 1 || !(args[0].equalsIgnoreCase("true") || args[0].equalsIgnoreCase("false"))) {
            return false; // shows usage
        }
        boolean enabled = Boolean.parseBoolean(args[0].toLowerCase(Locale.ROOT));
        settings.setEnabled(enabled);
        save();
        sender.sendMessage(Component.text("Copper rails " + (enabled ? "enabled." : "disabled. Carts return to normal speed as they move."),
            NamedTextColor.GREEN));
        return true;
    }

    private boolean multiplier(CommandSender sender, String[] args) {
        if (args.length == 0) {
            StringBuilder sb = new StringBuilder("Copper top-speed multipliers:");
            for (OxidationState state : OxidationState.values()) {
                sb.append("\n  ").append(state.key()).append(": ").append(format(settings.multiplier(state))).append('x');
            }
            if (!settings.enabled()) {
                sb.append("\n(Copper rails are currently disabled.)");
            }
            sender.sendMessage(Component.text(sb.toString(), NamedTextColor.YELLOW));
            return true;
        }
        OxidationState state = OxidationState.parse(args[0]);
        if (state == null) {
            sender.sendMessage(Component.text("Unknown oxidation state '" + args[0] + "'. Use unoxidized, exposed, weathered or oxidized.",
                NamedTextColor.RED));
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(Component.text(state.key() + ": " + format(settings.multiplier(state)) + "x", NamedTextColor.YELLOW));
            return true;
        }
        if (args.length > 2) {
            return false;
        }
        double value;
        try {
            value = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("'" + args[1] + "' is not a number.", NamedTextColor.RED));
            return true;
        }
        if (!CopperSpeedSettings.isValidMultiplier(value)) {
            sender.sendMessage(Component.text("The multiplier must be above 0 and at most " + format(CopperSpeedSettings.MAX_MULTIPLIER)
                + " (vanilla carts can't go faster than that).", NamedTextColor.RED));
            return true;
        }
        settings.setMultiplier(state, value);
        save();
        double blocksPerSecond = settings.baseMaxSpeed() * value * 20;
        sender.sendMessage(Component.text("Set " + state.key() + " copper to " + format(value) + "x (top speed "
            + format(blocksPerSecond) + " blocks/s).", NamedTextColor.GREEN));
        if (value > CopperSpeedSettings.SAFE_MULTIPLIER) {
            sender.sendMessage(Component.text("Warning: above " + format(CopperSpeedSettings.SAFE_MULTIPLIER)
                + "x, carts move a block or more per tick and will skip curves and slopes.", NamedTextColor.GOLD));
        }
        return true;
    }

    private void save() {
        settings.writeTo(plugin.getConfig());
        plugin.saveConfig();
    }

    private static String format(double d) {
        String s = BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.contains(".") ? s : s + ".0";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> options = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length == 1 && name.equals("copperrails")) {
            options.addAll(List.of("true", "false"));
        } else if (args.length == 1 && name.equals("coppermultiplier")) {
            for (OxidationState state : OxidationState.values()) {
                options.add(state.key());
            }
        } else if (args.length == 2 && name.equals("coppermultiplier")) {
            OxidationState state = OxidationState.parse(args[0]);
            if (state != null) {
                options.add(format(settings.multiplier(state)));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.startsWith(prefix));
        return options;
    }
}
