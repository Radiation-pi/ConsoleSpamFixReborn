package link.star_dust.consolefix;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import link.star_dust.consolefix.FoliaCheck;

import java.util.Objects;
import java.util.logging.Logger;

public final class CSF extends JavaPlugin {
    public static Logger log;
    public static String pluginName;
    private ServerVersion serverVersion;
    private static ConfigHandler cH;
    private static EngineInterface eng;

    static {
        pluginName = "ConsoleSpamFixReborn";
    }

    @Override
    public void onEnable() {
        log = this.getLogger();
        log.info("Initializing " + pluginName);

        this.detectServerVersion();
        cH = new ConfigHandler(this);
        eng = serverVersion.usesOldEngine() ? new OldEngine(this) : new NewEngine(this);
        CommandHandler cmd = new CommandHandler(this);
        
        int pluginId = 24348;
        new Metrics(this, pluginId);

        // Debugging start
        log.info("Attempting to register command executor for 'csf'");
        if (this.getCommand("csf") == null) {
            log.severe("Command 'csf' could not be found! Make sure it is defined in plugin.yml.");
        } else {
            log.info("Command 'csf' found. Setting executor...");
            Objects.requireNonNull(this.getCommand("csf"), "Command 'csf' not found in plugin.yml").setExecutor(cmd);
            log.info("Command executor for 'csf' set successfully.");
        }
        // Debugging end

        this.getEngine().hideConsoleMessages();
        log.info(pluginName + " loaded successfully!");
    }

    @Override
    public void onDisable() {
    	if (!FoliaCheck.isFolia()) {
            Bukkit.getScheduler().cancelTasks(this);
    	} 
        HandlerList.unregisterAll(this);
        log.info("Messages hidden since the server started: " + this.getEngine().getHiddenMessagesCount());
        log.info(pluginName + " is disabled!");
    }

    private void detectServerVersion() {
        String[] versionParts = Bukkit.getBukkitVersion().split("-");
        String version = versionParts[0];
        log.info("Server version detected: " + version);

        this.serverVersion = ServerVersion.fromVersionString(version);
        log.info("Using engine type: " + serverVersion.getEngineType() + " for server version " + serverVersion);
    }

    public ConfigHandler getConfigHandler() {
        return cH;
    }

    public EngineInterface getEngine() {
        return eng;
    }
}
