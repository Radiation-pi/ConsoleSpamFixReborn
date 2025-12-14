package link.star_dust.consolefix.velocity;

import com.velocitypowered.api.command.SimpleCommand;

import org.spongepowered.configurate.serialize.SerializationException;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import link.star_dust.consolefix.velocity.LogFilter;

public class VelocityCommandHandler implements SimpleCommand {
    private final ConfigHandler configHandler;
    private final VelocityCSF velocityCSF;
	private final LogFilter logFilter;
	private final LogFilterManager logFilterManager;

    public VelocityCommandHandler(ConfigHandler configHandler, EngineInterface enginem, VelocityCSF velocityCSF, LogFilter logFilter, LogFilterManager logFilterManager) throws SerializationException {
    	this.velocityCSF = velocityCSF;
        this.configHandler = configHandler;
        this.logFilterManager = new LogFilterManager(velocityCSF);
        this.logFilter = new LogFilter(velocityCSF);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        // 检查权限
        if (!hasPermission(invocation)) {
            source.sendMessage(Component.text("You don't have permission to do that."));
            return;
        }

        // 检查参数
        if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) {
            source.sendMessage(Component.text("Reload Config: /csfv reload"));
            return;
        }

        boolean success = configHandler.loadConfig();
        if (!success) {
            source.sendMessage(Component.text("Failed to reload the config. Check the console for errors."));
            return;
        }

        // 检查空值
        if (logFilter == null) {
            source.sendMessage(Component.text("LogFilter is not initialized. Reload failed."));
            return;
        }

        try {
            logFilterManager.updateFilter(velocityCSF.getConfigHandler().getStringList("Messages-To-Hide-Filter"));
        } catch (SerializationException e) {
            e.printStackTrace();
        }
        source.sendMessage(Component.text("Reload successful!"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Check if the command source has the required permission
        return invocation.source().hasPermission("csf.admin");
    }
}