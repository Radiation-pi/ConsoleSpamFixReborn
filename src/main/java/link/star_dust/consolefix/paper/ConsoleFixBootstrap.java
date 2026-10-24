package link.star_dust.consolefix.paper;

import io.papermc.paper.plugin.bootstrap.*;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConsoleFixBootstrap implements PluginBootstrap {
    private FilterRuntime runtime;
    @Override
    public void bootstrap(BootstrapContext context) {
        runtime = new FilterRuntime(context.getDataDirectory(), message -> context.getLogger().error(message));
        runtime.reach(Settings.Stage.BOOTSTRAP);
        context.getLogger().info("Console filter bootstrap ready: {}", runtime.status());
    }
    @Override
    public JavaPlugin createPlugin(PluginProviderContext context) {
        try { return new ConsoleFixPlugin(runtime); }
        catch (RuntimeException | Error e) { runtime.close(); throw e; }
    }
}
