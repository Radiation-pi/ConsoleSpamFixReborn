package link.star_dust.consolefix.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.plugin.Command;

public class BungeeCommandHandler extends Command {
    private final ConfigHandler configHandler;
    private final EngineInterface engine;
    private final BungeeCSF plugin;

    public BungeeCommandHandler(ConfigHandler configHandler, EngineInterface engine, BungeeCSF plugin) {
        super("csfb");
        this.configHandler = configHandler;
        this.engine = engine;
        this.plugin = plugin;
    }

@Override
public void execute(CommandSender sender, String[] args) {
    if (!sender.hasPermission("csf.admin")) {
        sender.sendMessage(new ComponentBuilder("You don't have permission to do that.")
            .color(net.md_5.bungee.api.ChatColor.RED)
            .create());
        return;
    }

    if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
        sender.sendMessage(new ComponentBuilder("Usage: /csfb reload")
            .color(net.md_5.bungee.api.ChatColor.YELLOW)
            .create());
        return;
    }

    boolean success = configHandler.loadConfig();
    if (!success) {
        sender.sendMessage(new ComponentBuilder("Failed to reload the config. Check the console for errors.")
            .color(net.md_5.bungee.api.ChatColor.RED)
            .create());
        return;
    }

    plugin.updateLogFilter();
    sender.sendMessage(new ComponentBuilder("Reload successful!")
        .color(net.md_5.bungee.api.ChatColor.GREEN)
        .create());
    }
}