package me.leoko.advancedban.manager;

import me.leoko.advancedban.Universal;
import me.leoko.advancedban.utils.Command;

/**
 * The Command Manager is used to handle commands based on the sender, command-name and arguments.
 */
public class CommandManager {

    private static CommandManager instance = null;

    /**
     * Get the instance of the command manager
     *
     * @return the command manager instance
     */
    public static synchronized CommandManager get() {
        return instance == null ? instance = new CommandManager() : instance;
    }

    /**
     * Handle/Perform a command.
     *
     * @param sender the sender which executes the command
     * @param cmd    the command name
     * @param args   the arguments for this command
     */
    public void onCommand(final Object sender, final String cmd, final String[] args) {
        Universal.get().getMethods().runAsync(() -> {
            if (Universal.get().getRuntimeRole().isAgent()) {
                if (!Universal.get().getMethods().submitAuthorityCommand(sender, cmd, args)) {
                    Universal.get().getMethods().sendMessage(sender,
                            "§c[AdvancedBan] Authority unavailable; no local punishment was changed.");
                }
                return;
            }
            executeNow(sender, cmd, args);
        });
    }

    /**
     * Executes on the caller's already-asynchronous transport thread. This is
     * used by the Authority bridge so the complete console response can be
     * returned before the request is acknowledged.
     */
    public boolean executeNow(Object sender, String cmd, String[] args) {
        if (Universal.get().getRuntimeRole().isAgent()) {
            return false;
        }
        Command command = Command.getByName(cmd);
        if (command == null) {
            return false;
        }
        String permission = command.getPermission();
        if (permission != null && !Universal.get().hasPerms(sender, permission)) {
            MessageManager.sendMessage(sender, "General.NoPerms", true);
            return true;
        }
        if (!command.validateArguments(args)) {
            MessageManager.sendMessage(sender, command.getUsagePath(), true);
            return true;
        }
        command.execute(sender, args);
        return true;
    }
}
