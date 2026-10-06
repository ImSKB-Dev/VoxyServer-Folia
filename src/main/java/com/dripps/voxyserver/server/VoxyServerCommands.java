package com.dripps.voxyserver.server;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class VoxyServerCommands implements CommandExecutor, TabCompleter {
    private final Supplier<WorldImportCoordinator> coordinatorSupplier;

    public VoxyServerCommands(Supplier<WorldImportCoordinator> coordinatorSupplier) {
        this.coordinatorSupplier = coordinatorSupplier;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voxyserver.admin")) {
            sender.sendMessage("§cYou do not have permission to execute this command.");
            return true;
        }

        WorldImportCoordinator coordinator = coordinatorSupplier.get();
        if (coordinator == null) {
            sender.sendMessage("§cVoxyServer engine is not currently active.");
            return true;
        }

        if (args.length >= 2 && args[0].equalsIgnoreCase("import") && args[1].equalsIgnoreCase("existing")) {
            if (args.length == 3 && args[2].equalsIgnoreCase("all")) {
                coordinator.startAll(sender);
                return true;
            } else if (args.length == 3 && args[2].equalsIgnoreCase("current")) {
                coordinator.startCurrent(sender);
                return true;
            } else if (args.length == 4 && args[2].equalsIgnoreCase("dimension")) {
                coordinator.startDimension(sender, args[3]);
                return true;
            } else if (args.length == 3 && args[2].equalsIgnoreCase("status")) {
                if (coordinator.isImportRunning()) {
                    sender.sendMessage("§a[VoxyServer] An import is currently running.");
                } else {
                    sender.sendMessage("§a[VoxyServer] No import is running.");
                }
                return true;
            } else if (args.length == 3 && args[2].equalsIgnoreCase("cancel")) {
                coordinator.cancel(sender);
                return true;
            }
        }

        sender.sendMessage("§6=== VoxyServer Commands ===");
        sender.sendMessage("§e/voxyserver import existing all §7- Import all loaded dimensions");
        sender.sendMessage("§e/voxyserver import existing current §7- Import current dimension");
        sender.sendMessage("§e/voxyserver import existing dimension <dim> §7- Import specific dimension");
        sender.sendMessage("§e/voxyserver import existing status §7- Check import status");
        sender.sendMessage("§e/voxyserver import existing cancel §7- Cancel running import");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("import");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            completions.add("existing");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("import") && args[1].equalsIgnoreCase("existing")) {
            completions.add("all");
            completions.add("current");
            completions.add("dimension");
            completions.add("status");
            completions.add("cancel");
        }
        return completions;
    }
}
