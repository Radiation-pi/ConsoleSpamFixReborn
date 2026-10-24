package link.star_dust.consolefix.paper;

import java.io.IOException;
import java.util.*;
import io.papermc.paper.command.brigadier.*;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.event.*;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConsoleFixPlugin extends JavaPlugin implements Listener, BasicCommand {
    private final FilterRuntime runtime;
    public ConsoleFixPlugin(FilterRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }
    @Override
    public void onLoad() { runtime.reach(Settings.Stage.ON_LOAD); }
    @Override
    public void onEnable() {
        try {
            runtime.reach(Settings.Stage.ON_ENABLE);
            getServer().getPluginManager().registerEvents(this, this);
            registerCommand("csf", "Console filter management", this);
            getLogger().info(runtime.status());
        } catch (RuntimeException | Error e) { runtime.close(); throw e; }
    }
    @EventHandler
    public void loaded(ServerLoadEvent event) { runtime.running(); }
    @Override
    public void onDisable() { runtime.close(); }
    @Override
    public String permission() { return "csf.admin"; }
    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? List.of("status", "reload", "stats", "test").stream()
            .filter(s -> args.length == 0 || s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList() : List.of();
    }
    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(permission())) return;
        String cmd = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "status" -> sender.sendMessage(runtime.status());
            case "stats" -> {
                sender.sendMessage("Hidden since startup: " + runtime.hidden() + "; per-rule counts since reload:");
                runtime.counters().forEach((id, count) -> sender.sendMessage(id + ": " + count));
            }
            case "reload" -> {
                try { sender.sendMessage(runtime.reload()); }
                catch (IOException e) { sender.sendMessage("Reload failed; previous rules remain active: " + e.getMessage()); }
            }
            case "test" -> {
                if (args.length < 4 || Level.getLevel(args[1].toUpperCase(Locale.ROOT)) == null) {
                    sender.sendMessage("Usage: /csf test <LEVEL> <logger> <message>"); return;
                }
                var event = Log4jLogEvent.newBuilder().setLevel(Level.getLevel(args[1].toUpperCase(Locale.ROOT)))
                    .setLoggerName(args[2]).setMessage(new SimpleMessage(String.join(" ", Arrays.copyOfRange(args, 3, args.length)))).build();
                sender.sendMessage(runtime.test(event) + " (dry run; no Throwable supplied; counters unchanged)");
            }
            default -> sender.sendMessage("Usage: /csf [status|reload|stats|test]");
        }
    }
}
