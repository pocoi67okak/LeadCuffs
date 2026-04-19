package com.prison.leadcuffs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * /uncuff <player> — forcefully release a cuffed player (admin command).
 */
public class UncuffCommand implements CommandExecutor {

    private final CuffManager cuffManager;

    public UncuffCommand(CuffManager cuffManager) {
        this.cuffManager = cuffManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Использование: /uncuff <игрок>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Игрок " + args[0] + " не найден или не в сети.");
            return true;
        }

        if (!cuffManager.isCuffed(target.getUniqueId())) {
            sender.sendMessage(ChatColor.RED + "Игрок " + target.getName() + " не скован.");
            return true;
        }

        cuffManager.release(target.getUniqueId());

        sender.sendMessage(ChatColor.GREEN + "✔ Вы сняли наручники с " + target.getName());
        target.sendMessage(ChatColor.GREEN + "✔ Наручники были сняты администратором. Вы свободны!");

        return true;
    }
}
