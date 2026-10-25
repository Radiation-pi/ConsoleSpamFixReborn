package link.star_dust.consolefix.paper;

import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.config.*;
import org.apache.logging.log4j.core.filter.AbstractFilter;

final class FilterRuntime implements AutoCloseable {
    private static final long STACK_CONTINUATION_WINDOW_NANOS = 2_000_000_000L;
    private static final Pattern STACK_CONTINUATION = Pattern.compile(
        "^\\s*(?:(?:[\\w$]+\\.)*[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable|Failure)(?::\\s+.*)?|"
            + "Caused by:\\s+.+|Suppressed:\\s+.+|at\\s+.+\\([^)]*\\)|\\.\\.\\.\\s+\\d+\\s+more)\\s*$"
    );
    private record Continuation(String ruleId, long expiresAt) {}
    private record State(Settings settings, Map<String, LongAdder> counters, ArchiveWriter archive) {}
    private final ConfigFile configFile;
    private final Path directory;
    private final LongAdder hidden = new LongAdder();
    private final PropertyChangeListener configurationChanged = e -> {
        if ("config".equals(e.getPropertyName())) reattach();
    };
    private volatile State state;
    private volatile Rule.Phase phase = Rule.Phase.STARTUP;
    private volatile boolean closed;
    private volatile String configError;
    private Settings.Stage startedStage;
    private LoggerContext context;
    private LoggerConfig attached;
    private final Map<String, Continuation> continuations = new ConcurrentHashMap<>();
    private final AbstractFilter filter = new AbstractFilter() {
        @Override
        public Result filter(LogEvent event) {
            if (closed) return Result.NEUTRAL;
            FilterRuntime.State current = state;
            Rule match = match(current, event);
            String thread = threadKey(event);
            if (match != null && match.action() == Rule.Action.KEEP) {
                continuations.remove(thread);
                return Result.NEUTRAL;
            }
            Continuation continuation = continuations.get(thread);
            long now = System.nanoTime();
            if (continuation != null && continuation.expiresAt() <= now) {
                continuations.remove(thread, continuation);
                continuation = null;
            }
            if (match == null && continuation != null && STACK_CONTINUATION.matcher(message(event)).matches()) {
                Continuation active = continuation;
                match = current.settings().rules().stream()
                    .filter(rule -> rule.id().equals(active.ruleId())).findFirst().orElse(null);
                if (match == null) {
                    continuations.remove(thread, continuation);
                    return Result.NEUTRAL;
                }
            }
            if (match == null || match.action() == Rule.Action.KEEP) {
                continuations.remove(thread);
                return Result.NEUTRAL;
            }
            hidden.increment();
            current.counters().get(match.id()).increment();
            if (current.archive() != null) current.archive().offer(match.id(), event);
            continuations.put(thread, new Continuation(match.id(), now + STACK_CONTINUATION_WINDOW_NANOS));
            return Result.DENY;
        }
    };

    FilterRuntime(Path directory, Consumer<String> diagnostic) {
        this.directory = directory;
        this.configFile = new ConfigFile(directory);
        state = new State(new Settings(Settings.Stage.BOOTSTRAP, List.of(),
            new Settings.Archive(false, 2048, 16777216, 4)), Map.of(), null);
        try { reload(); }
        catch (IOException e) { configError = e.getMessage(); diagnostic.accept("No rules loaded: " + configError); }
        startedStage = state.settings().stage();
    }

    synchronized String reload() throws IOException {
        if (closed) throw new IOException("ConsoleFix is closed");
        // Everything that can fail is prepared before publishing the new snapshot.
        Settings next;
        try { next = configFile.load(); }
        catch (IOException e) { configError = e.getMessage(); throw e; }
        State old = state;
        ArchiveWriter archive = old.archive();
        boolean restart = startedStage != null && (startedStage != next.stage()
            || !old.settings().archive().equals(next.archive()));
        if (startedStage == null && next.archive().enabled()) {
            archive = next.archive().enabled() ? new ArchiveWriter(directory.resolve("filtered-logs"), next.archive()) : null;
        }
        if (startedStage != null) next = new Settings(startedStage, next.rules(), old.settings().archive());
        Map<String, LongAdder> counters = new LinkedHashMap<>();
        for (Rule r : next.rules()) counters.put(r.id(), new LongAdder());
        state = new State(next, Collections.unmodifiableMap(counters), archive);
        continuations.clear();
        configError = null;
        if (old.archive() != null && old.archive() != archive) old.archive().close();
        return restart ? "Rules reloaded; installation/archive changes require restart." : "Rules reloaded.";
    }

    synchronized void reach(Settings.Stage stage) {
        if (!closed && context == null && startedStage == stage) {
            context = (LoggerContext) LogManager.getContext(false);
            context.addPropertyChangeListener(configurationChanged);
            filter.start();
            reattach();
        }
    }

    private synchronized void reattach() {
        if (closed || context == null) return;
        LoggerConfig root = context.getConfiguration().getRootLogger();
        if (attached == root) return;
        if (attached != null) attached.removeFilter(filter);
        root.addFilter(filter);
        attached = root;
        context.updateLoggers();
    }

    void running() { phase = Rule.Phase.RUNNING; }
    private Rule match(State current, LogEvent event) {
        // Administrative diagnostics must stay visible even with broad user rules.
        if ("ConsoleSpamFixReborn".equals(event.getLoggerName())) return null;
        for (Rule rule : current.settings().rules()) if (rule.matches(event, phase)) return rule;
        return null;
    }
    private static String threadKey(LogEvent event) {
        return Objects.toString(event.getThreadName(), "<unknown-thread>");
    }
    private static String message(LogEvent event) {
        return event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
    }
    String test(LogEvent event) {
        Rule rule = match(state, event);
        return rule == null ? "PASS (no matching rule)" : rule.action() + " (" + rule.id() + ")";
    }
    long hidden() { return hidden.sum(); }
    Map<String, Long> counters() {
        Map<String, Long> result = new LinkedHashMap<>();
        state.counters().forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }
    synchronized String status() {
        State s = state;
        return "stage=" + startedStage + ", installed=" + (attached != null) + ", phase=" + phase
            + ", rules=" + s.settings().rules().size() + ", hidden=" + hidden()
            + (s.archive() == null ? ", archive=off" : ", archiveDropped=" + s.archive().discarded()
                + ", archiveError=" + s.archive().failure())
            + (configError == null ? "" : ", configError=" + configError);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (context != null) {
            context.removePropertyChangeListener(configurationChanged);
            if (attached != null) attached.removeFilter(filter);
            context.updateLoggers();
        }
        attached = null;
        filter.stop();
        if (state.archive() != null) state.archive().close();
    }
}
