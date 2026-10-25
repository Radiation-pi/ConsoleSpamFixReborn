package link.star_dust.consolefix.paper;

import java.util.*;
import java.util.regex.Pattern;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;

record Rule(String id, Set<Phase> phases, Set<String> loggers, Set<Level> levels,
            String equals, List<String> contains, List<Pattern> regex,
            List<Cause> causes, Action action) {
    enum Phase { STARTUP, RUNNING }
    enum Action { DROP, KEEP }
    record Cause(String className, List<String> messages) {
        boolean matches(Throwable thrown) {
            Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable t = thrown; t != null && seen.size() < 64 && seen.add(t); t = t.getCause()) {
                String message = Objects.toString(t.getMessage(), "");
                if (t.getClass().getName().equals(className)
                    && (messages.isEmpty() || messages.stream().anyMatch(message::contains))) return true;
            }
            return false;
        }
    }
    boolean matches(LogEvent event, Phase phase) {
        if (!phases.isEmpty() && !phases.contains(phase)) return false;
        if (!loggers.isEmpty() && !loggers.contains(event.getLoggerName())) return false;
        if (!levels.isEmpty() && !levels.contains(event.getLevel())) return false;
        String message = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (event.getThrown() != null) {
            StringWriter trace = new StringWriter();
            event.getThrown().printStackTrace(new PrintWriter(trace));
            message += "\n" + trace;
        }
        final String fullMessage = message;
        String baseMessage = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
        if (equals != null && !equals.equals(baseMessage)) return false;
        if (!contains.isEmpty() && contains.stream().noneMatch(fullMessage::contains)) return false;
        if (!regex.isEmpty() && regex.stream().noneMatch(p -> p.matcher(fullMessage).find())) return false;
        return causes.isEmpty() || causes.stream().anyMatch(c -> c.matches(event.getThrown()));
    }
}
