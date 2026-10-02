package fr.xephi.authme.command;

import fr.xephi.authme.permission.PermissionsManager;
import fr.xephi.authme.service.BukkitService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Provides tab completion suggestions for all AuthMe commands based on the command tree:
 * child labels of the command currently being typed (filtered by permission and by the
 * text already entered) and, where applicable, online player names.
 */
public class TabCompleteHandler implements TabCompleter {

    /**
     * Conversion jobs available to {@code /authme converter}.
     */
    private static final String[] CONVERTER_JOBS =
        {"xauth", "crazylogin", "rakamak", "royalauth", "vauth", "sqliteToSql", "mysqlToSqlite", "loginsecurity"};

    /**
     * Debug sections available to {@code /authme debug} (must match DebugSection#getName()).
     */
    private static final String[] DEBUG_SECTIONS =
        {"perm", "stats", "country", "db", "valid", "limbo", "mail", "spawn", "mysqldef"};

    /**
     * Subcommands available to {@code /authme emailchange}.
     */
    private static final String[] EMAILCHANGE_SUBCOMMANDS =
        {"view", "set", "delete", "accounts", "deleteaccount"};

    private final CommandInitializer commandInitializer;
    private final PermissionsManager permissionsManager;
    private final BukkitService bukkitService;

    @Inject
    TabCompleteHandler(CommandInitializer commandInitializer, PermissionsManager permissionsManager,
                       BukkitService bukkitService) {
        this.commandInitializer = commandInitializer;
        this.permissionsManager = permissionsManager;
        this.bukkitService = bukkitService;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String alias, String[] args) {
        CommandDescription current = findBaseCommand(command.getName());
        if (current == null) {
            return Collections.emptyList();
        }

        // The last argument is the token being completed; everything before it is fixed context.
        String partial = "";
        List<String> fixedTokens = new ArrayList<>();
        fixedTokens.add(command.getName());
        if (args != null) {
            for (int i = 0; i < args.length - 1; i++) {
                fixedTokens.add(args[i]);
            }
            if (args.length > 0) {
                partial = args[args.length - 1];
            }
        }

        // Descend the command tree while the fixed tokens match child labels.
        int labelsConsumed = 1; // the base command label itself
        for (int i = 1; i < fixedTokens.size(); i++) {
            CommandDescription child = findChild(current, fixedTokens.get(i));
            if (child == null) {
                break;
            }
            current = child;
            labelsConsumed++;
        }

        // Position of the token being completed among the command's arguments.
        int totalTokens = fixedTokens.size() + 1;
        int argumentIndex = totalTokens - 1 - labelsConsumed;

        TreeSet<String> suggestions = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        // Suggest child labels (e.g. the sub commands of /authme or /email)
        for (CommandDescription child : current.getChildren()) {
            if (permissionsManager.hasPermission(sender, child.getPermission())) {
                addIfPrefixMatches(suggestions, child.getLabels().get(0), partial);
            }
        }

        // Suggest argument values where we have something useful to offer
        List<CommandArgumentDescription> arguments = current.getArguments();
        if (argumentIndex >= 0 && argumentIndex < arguments.size()) {
            String argumentName = arguments.get(argumentIndex).getName().toLowerCase(Locale.ROOT);
            if ("subcommand".equals(argumentName)) {
                for (String subcommand : EMAILCHANGE_SUBCOMMANDS) {
                    addIfPrefixMatches(suggestions, subcommand, partial);
                }
            } else if (argumentName.contains("player") && !partial.contains("@")) {
                // A partial starting with "@" suggests the user is typing an email
                // address (e.g. for /authme emailchange accounts), so no player names are offered
                for (Player onlinePlayer : bukkitService.getOnlinePlayers()) {
                    addIfPrefixMatches(suggestions, onlinePlayer.getName(), partial);
                }
            } else if ("job".equals(argumentName)) {
                for (String job : CONVERTER_JOBS) {
                    addIfPrefixMatches(suggestions, job, partial);
                }
            } else if ("mode".equals(argumentName)) {
                addIfPrefixMatches(suggestions, "ON", partial);
                addIfPrefixMatches(suggestions, "OFF", partial);
            } else if ("child".equals(argumentName)) {
                for (String section : DEBUG_SECTIONS) {
                    addIfPrefixMatches(suggestions, section, partial);
                }
            }
        }

        return new ArrayList<>(suggestions);
    }

    /**
     * Returns the base command description for the given label, or null if the
     * label is not an AuthMe base command.
     *
     * @param label the command label (e.g. "authme" for "/authme")
     * @return the base command description, or null
     */
    private CommandDescription findBaseCommand(String label) {
        String baseLabel = label.toLowerCase(Locale.ROOT);
        if (baseLabel.startsWith("authme:")) {
            baseLabel = baseLabel.substring("authme:".length());
        }
        for (CommandDescription command : commandInitializer.getCommands()) {
            if (command.hasLabel(baseLabel)) {
                return command;
            }
        }
        return null;
    }

    /**
     * Returns the child of the given command which has the given label, or null.
     *
     * @param command the parent command
     * @param label the label to look for
     * @return the child with the given label, or null
     */
    private static CommandDescription findChild(CommandDescription command, String label) {
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        for (CommandDescription child : command.getChildren()) {
            if (child.hasLabel(lowerLabel)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Adds the suggestion to the set if it matches the prefix being typed.
     *
     * @param suggestions the set of suggestions to add to
     * @param suggestion the candidate suggestion
     * @param partial the prefix the user has typed so far
     */
    private static void addIfPrefixMatches(TreeSet<String> suggestions, String suggestion, String partial) {
        if (partial == null || partial.isEmpty()
                || suggestion.toLowerCase(Locale.ROOT).startsWith(partial.toLowerCase(Locale.ROOT))) {
            suggestions.add(suggestion);
        }
    }
}
