package me.leoko.advancedban.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import me.leoko.advancedban.Universal;
import me.leoko.advancedban.manager.CommandManager;
import me.leoko.advancedban.utils.tabcompletion.TabCompleter;

import java.util.Collections;
import java.util.List;

final class VelocityCommand implements SimpleCommand {
    private final String name;
    private final String permission;
    private final TabCompleter tabCompleter;

    VelocityCommand(String name, String permission, TabCompleter tabCompleter) {
        this.name = name;
        this.permission = permission;
        this.tabCompleter = tabCompleter;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments().clone();
        if (args.length > 0) {
            Object target = Universal.get().getMethods().getPlayer(args[0]);
            args[0] = target == null ? args[0] : Universal.get().getMethods().getName(target);
        }
        CommandManager.get().onCommand(invocation.source(), name, args);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (tabCompleter == null || (permission != null && !Universal.get().hasPerms(invocation.source(), permission))) {
            return Collections.emptyList();
        }
        return tabCompleter.onTabComplete(invocation.source(), invocation.arguments());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Preserve AdvancedBan 2.3.0's own permission handling, including ab.*
        // and its configurable General.NoPerms response.
        return true;
    }
}
