package link.star_dust.consolefix.paper;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.apache.logging.log4j.*;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.*;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;

public final class BehaviorTest {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
        System.out.println("PASS " + name);
    }
    private static Path config(String text) throws IOException {
        Path path = Files.createTempDirectory("consolefix-test-");
        Files.writeString(path.resolve("config.yml"), text);
        return path;
    }
    private static String rules(String yaml) {
        return "schema-version: 1\ninstallation:\n  stage: BOOTSTRAP\nrules:\n" + yaml;
    }
    private static LogEvent event(String logger, String message, Throwable t) {
        return Log4jLogEvent.newBuilder().setLoggerName(logger).setLevel(Level.ERROR)
            .setMessage(new SimpleMessage(message)).setThrown(t).build();
    }
    private static final class Capture extends AbstractAppender {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        Capture() { super("capture", null, null, false, Property.EMPTY_ARRAY); start(); }
        public void append(LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
    }
    private record Rig(LoggerContext context, Capture capture, LoggerConfig root) {}
    private static Rig rig() {
        LoggerContext c = (LoggerContext) LogManager.getContext(false);
        DefaultConfiguration config = new DefaultConfiguration();
        LoggerConfig root = config.getRootLogger();
        for (String name : List.copyOf(root.getAppenders().keySet())) root.removeAppender(name);
        root.setLevel(Level.ALL);
        Capture capture = new Capture();
        config.addAppender(capture); root.addAppender(capture, Level.ALL, null);
        c.start(config);
        return new Rig(c, capture, root);
    }

    private static void migration() throws Exception {
        String old = "Messages-To-Hide-Filter:\n- 'custom timeout'\n- '第二条'\nChatMessages:\n  CmdHelp: 'custom help'\n";
        Path path = config(old);
        Settings migrated = new ConfigFile(path).load();
        check(migrated.rules().size() == 2 && migrated.rules().getFirst().contains().equals(List.of("custom timeout")), "1.x rules preserved");
        Path backup;
        try (var stream = Files.list(path)) { backup = stream.filter(p -> p.toString().endsWith(".bak")).findFirst().orElseThrow(); }
        check(Files.readString(backup).equals(old), "migration backup is byte-for-byte original");
        check(Files.readString(path.resolve("config.yml")).contains("custom help"), "custom legacy settings retained for review");
        check(new ConfigFile(path).load().rules().size() == 2, "migration is idempotent");
        Path v2 = config("_config-version: 2\nMessages-To-Hide-Filter:\n  contains: ['custom']\n  regex: ['^pattern$']\nLog-Filtered-Messages: true\n");
        Settings s = new ConfigFile(v2).load();
        check(s.rules().size() == 2 && s.archive().enabled(), "2.x regex and archive flag migrate");
        Path invalid = config("Messages-To-Hide-Filter: ['']\n");
        String original = Files.readString(invalid.resolve("config.yml"));
        try { new ConfigFile(invalid).load(); throw new AssertionError("empty keyword accepted"); }
        catch (IOException expected) { check(Files.readString(invalid.resolve("config.yml")).equals(original), "invalid legacy rule never overwrites source"); }
    }

    private static void matchAndReload() throws Exception {
        Path path = config(rules("""
              - id: keep-admin
                message-contains-any: ['allow']
                action: KEEP
              - id: scoped-timeout
                logger-names: [authlib]
                levels: [ERROR]
                message-equals: 'public-key failure'
                throwable-any:
                  - class: java.net.SocketTimeoutException
                    message-contains-any: ['timed out']
              - id: startup-only
                phases: [STARTUP]
                message-contains-any: ['startup-noise']
              - id: generic
                message-contains-any: ['noise']
            """));
        Rig rig = rig();
        try (FilterRuntime runtime = new FilterRuntime(path, m -> { throw new AssertionError(m); })) {
            runtime.reach(Settings.Stage.BOOTSTRAP);
            check(runtime.test(event("authlib", "public-key failure", new IOException("wrapper", new java.net.SocketTimeoutException("Connect timed out")))).startsWith("DROP"), "matches nested Throwable with logger and message");
            check(runtime.test(event("authlib", "public-key failure", new IOException("certificate"))).startsWith("PASS"), "different cause remains visible");
            check(runtime.test(event("another", "public-key failure", new java.net.SocketTimeoutException("Connect timed out"))).startsWith("PASS"), "logger constraint prevents unrelated suppression");
            check(runtime.hidden() == 0, "dry run does not change counters");
            LogManager.getLogger("authlib").error("public-key failure", new java.net.SocketTimeoutException("Connect timed out"));
            LogManager.getLogger("normal").error("allow noise");
            LogManager.getLogger("normal").error("startup-noise");
            check(runtime.hidden() == 2 && rig.capture.messages.equals(List.of("allow noise")), "first match KEEP and real log filtering");
            runtime.running();
            check(!runtime.test(event("x", "startup-noise", null)).contains("startup-only"), "startup rule stops matching after ServerLoad");
            for (String invalid : List.of(
                rules("  - id: broken\n    message-regex-any: ['[']\n"),
                rules("  - id: broken\n    message-contains-any: ['']\n"),
                rules("  - id: broken\n    typo-message: ['noise']\n"),
                rules("  - id: broken\n    phases: [RUNING]\n    message-equals: test\n"),
                rules("  - id: broken\n    enabled: flase\n    message-equals: test\n"),
                rules("  - id: broken\n    message-equals: test\n") + "archive:\n  queue-capacity: wrong\n",
                rules("  - id: broken\n"))) {
                Files.writeString(path.resolve("config.yml"), invalid);
                try { runtime.reload(); throw new AssertionError("invalid reload accepted"); }
                catch (IOException expected) { check(runtime.test(event("x", "noise", null)).startsWith("DROP"), "invalid reload keeps entire previous ruleset"); }
            }
            String updated = rules("  - id: replacement\n    message-contains-any: ['replacement']\n");
            Files.writeString(path.resolve("config.yml"), updated);
            runtime.reload(); runtime.reload();
            check(runtime.test(event("x", "noise", null)).startsWith("PASS"), "successful reload removes old rules");
            long before = runtime.hidden();
            LogManager.getLogger("normal").error("replacement");
            check(runtime.hidden() == before + 1, "repeated reload does not duplicate filter");
            Rig replacement = rig();
            LogManager.getLogger("normal").error("replacement");
            check(replacement.capture.messages.isEmpty() && runtime.hidden() == before + 2, "Log4j reconfiguration automatically reattaches filter");
            AbstractFilter other = new AbstractFilter() {};
            replacement.root.addFilter(other);
            runtime.close();
            LogManager.getLogger("normal").error("replacement");
            check(replacement.capture.messages.equals(List.of("replacement")), "disable restores log output");
            check(replacement.root.getFilter() == other, "disable preserves other filters");
        }
    }

    private static void regexListRules() throws Exception {
        Path path = config(rules("- 'java\\.net\\.ConnectException: 连接超时'\n- '.*Read timed out.*'\n- yyy\n"));
        Rig rig = rig();
        try (FilterRuntime runtime = new FilterRuntime(path, m -> { throw new AssertionError(m); })) {
            runtime.reach(Settings.Stage.BOOTSTRAP);
            check(runtime.test(event("x", "java.net.ConnectException: 连接超时", null)).equals("DROP (regex-1)"),
                "plain rule strings compile as substring regex and get generated ids");
            check(runtime.test(event("x", "prefix Read timed out suffix", null)).equals("DROP (regex-2)"),
                "any matching regex list entry drops the event");
            check(runtime.test(event("x", "prefix yyy suffix", null)).equals("DROP (regex-3)"),
                "plain unquoted YAML list scalars are accepted");
            check(runtime.test(event("x", "javaXnetXConnectException: 连接超时", null)).startsWith("PASS"),
                "escaped punctuation retains literal matching");
            LogManager.getLogger("normal").error("prefix yyy suffix");
            LogManager.getLogger("normal").error("still visible");
            check(runtime.hidden() == 1 && rig.capture.messages.equals(List.of("still visible")),
                "regex list filters real events and keeps unrelated logs visible");
            for (String invalid : List.of("- '['\n", "- ''\n", "- 123\n", "- yyy\n- id: advanced\n  message-equals: test\n")) {
                Files.writeString(path.resolve("config.yml"), rules(invalid));
                try { runtime.reload(); throw new AssertionError("invalid shorthand rule accepted"); }
                catch (IOException expected) {
                    check(runtime.test(event("x", "prefix yyy suffix", null)).equals("DROP (regex-3)"),
                        "invalid shorthand reload preserves previous rules");
                }
            }
            Files.writeString(path.resolve("config.yml"), rules("- '^new$'\n"));
            runtime.reload();
            check(runtime.test(event("x", "new", null)).startsWith("DROP")
                && runtime.test(event("x", "prefix new suffix", null)).startsWith("PASS")
                && runtime.test(event("x", "yyy", null)).startsWith("PASS"),
                "regex reload replaces old rules and honors anchors");
        }
        rig.context.stop();
    }

    private static void concurrencyAndStages() throws Exception {
        Rig rig = rig();
        Path path = config(rules("  - id: concurrent\n    message-equals: hidden\n"));
        try (FilterRuntime runtime = new FilterRuntime(path, m -> { throw new AssertionError(m); })) {
            runtime.reach(Settings.Stage.ON_ENABLE);
            LogManager.getLogger("normal").error("hidden");
            check(runtime.hidden() == 0, "installation respects selected stage");
            runtime.reach(Settings.Stage.BOOTSTRAP);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> workers = new ArrayList<>();
            for (int n = 0; n < 8; n++) {
                Thread worker = new Thread(() -> {
                    try { go.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
                    for (int i = 0; i < 2000; i++) LogManager.getLogger("normal").error("hidden");
                });
                workers.add(worker); worker.start();
            }
            go.countDown();
            for (Thread worker : workers) worker.join();
            check(runtime.hidden() == 16000 && runtime.counters().get("concurrent") == 16000L, "concurrent counts are exact");
            String changed = rules("  - id: new\n    message-equals: next\n").replace("stage: BOOTSTRAP", "stage: ON_ENABLE")
                + "archive:\n  enabled: true\n";
            Files.writeString(path.resolve("config.yml"), changed);
            check(runtime.reload().contains("restart"), "stage and archive changes explicitly require restart");
            check(runtime.status().contains("archive=off"), "archive stays consistent until restart");
        }
        rig.context.stop();
        Path invalid = config(rules("  - id: bad\n"));
        List<String> diagnostics = new ArrayList<>();
        try (FilterRuntime runtime = new FilterRuntime(invalid, diagnostics::add)) {
            check(runtime.test(event("x", "anything", null)).startsWith("PASS") && !diagnostics.isEmpty(), "bad startup config fails open with visible diagnostics");
        }
    }

    private static void archive() throws Exception {
        Path path = Files.createTempDirectory("consolefix-archive-");
        ArchiveWriter writer = new ArchiveWriter(path, new Settings.Archive(true, 128, 2048, 3));
        for (int n = 0; n < 20; n++) writer.offer("timeout", event("authlib", "x".repeat(200), new IOException("ORIGINAL-STACK")));
        writer.close();
        List<Path> files;
        try (var paths = Files.list(path)) { files = paths.toList(); }
        StringBuilder all = new StringBuilder();
        for (Path file : files) all.append(Files.readString(file));
        check(all.toString().contains("ORIGINAL-STACK") && all.toString().contains("java.io.IOException") && all.toString().contains("rule=timeout"), "archive preserves Throwable and rule id");
        check(files.size() <= 3 && writer.failure() == null, "archive rotates within configured file count");
        long before = writer.discarded();
        writer.offer("closed", event("x", "x", null));
        check(writer.discarded() == before + 1, "unavailable archive counts dropped entries");
    }
    public static void main(String[] args) throws Exception {
        migration(); matchAndReload(); regexListRules(); concurrencyAndStages(); archive();
        System.out.println("Completed " + checks + " checks");
    }
}
