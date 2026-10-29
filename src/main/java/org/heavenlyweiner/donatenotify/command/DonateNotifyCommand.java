package org.heavenlyweiner.donatenotify.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/** {@code /donatenotify reload | status <игрок> | preview <игрок> <порог>}; каждая подкоманда — своё право. */
public final class DonateNotifyCommand implements SimpleCommand {

    /** Действия, которые выполняет плагин; тяжёлая работа уходит в рабочий поток. */
    public interface Actions {
        Optional<String> reload();

        void status(CommandSource source, String player);

        void preview(CommandSource source, String player, String threshold);

        List<String> thresholdTokens();

        Collection<String> onlinePlayerNames();
    }

    static final TextColor BRAND = TextColor.color(0x95, 0x3E, 0xB2);
    static final TextColor NORMAL = TextColor.color(0xD6, 0xD6, 0xD6);
    static final TextColor ALERT = TextColor.color(0xFF, 0x6B, 0x6B);

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Actions actions;

    public DonateNotifyCommand(Actions actions) {
        this.actions = actions;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(source);
            case "status" -> status(source, args);
            case "preview" -> preview(source, args);
            default -> usage(source);
        }
    }

    private void reload(CommandSource source) {
        if (!require(source, Permissions.RELOAD)) return;
        actions.reload().ifPresentOrElse(
                error -> source.sendMessage(Component.text("Конфиг не применён, действует прежний: " + error, ALERT)),
                () -> source.sendMessage(Component.text("Конфиг HeavenlyDonateNotify перечитан.", NORMAL)));
    }

    private void status(CommandSource source, String[] args) {
        if (!require(source, Permissions.STATUS)) return;
        if (args.length != 2 || !PLAYER_NAME.matcher(args[1]).matches()) {
            source.sendMessage(Component.text("Использование: /donatenotify status <игрок>", NORMAL));
            return;
        }
        actions.status(source, args[1]);
    }

    private void preview(CommandSource source, String[] args) {
        if (!require(source, Permissions.PREVIEW)) return;
        if (args.length != 3 || !PLAYER_NAME.matcher(args[1]).matches()) {
            source.sendMessage(Component.text("Использование: /donatenotify preview <игрок> <порог>, порог: "
                    + String.join(", ", actions.thresholdTokens()) + ", expired", NORMAL));
            return;
        }
        actions.preview(source, args[1], args[2]);
    }

    private void usage(CommandSource source) {
        List<String> available = new ArrayList<>();
        if (source.hasPermission(Permissions.RELOAD)) available.add("reload");
        if (source.hasPermission(Permissions.STATUS)) available.add("status <игрок>");
        if (source.hasPermission(Permissions.PREVIEW)) available.add("preview <игрок> <порог>");
        if (available.isEmpty()) {
            source.sendMessage(Component.text("Недостаточно прав.", ALERT));
            return;
        }
        source.sendMessage(Component.text("HeavenlyDonateNotify: ", BRAND)
                .append(Component.text("/donatenotify " + String.join(" | ", available), NORMAL)));
    }

    private static boolean require(CommandSource source, String permission) {
        if (source.hasPermission(permission)) return true;
        source.sendMessage(Component.text("Недостаточно прав.", ALERT));
        return false;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        CommandSource source = invocation.source();
        return source.hasPermission(Permissions.RELOAD) || source.hasPermission(Permissions.STATUS)
                || source.hasPermission(Permissions.PREVIEW);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        CommandSource source = invocation.source();
        if (args.length <= 1) return filter(subcommands(source), args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean wantsPlayer = sub.equals("status") && source.hasPermission(Permissions.STATUS)
                || sub.equals("preview") && source.hasPermission(Permissions.PREVIEW);
        if (args.length == 2 && wantsPlayer) return filter(actions.onlinePlayerNames(), args[1]);
        if (args.length == 3 && sub.equals("preview") && source.hasPermission(Permissions.PREVIEW)) {
            List<String> tokens = new ArrayList<>(actions.thresholdTokens());
            tokens.add("expired");
            return filter(tokens, args[2]);
        }
        return List.of();
    }

    private static List<String> subcommands(CommandSource source) {
        List<String> result = new ArrayList<>();
        if (source.hasPermission(Permissions.RELOAD)) result.add("reload");
        if (source.hasPermission(Permissions.STATUS)) result.add("status");
        if (source.hasPermission(Permissions.PREVIEW)) result.add("preview");
        return result;
    }

    private static List<String> filter(Collection<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }
}
