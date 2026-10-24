package link.star_dust.consolefix.paper;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

final class ConfigFile {
    private final Path file;
    ConfigFile(Path directory) { file = directory.resolve("config.yml"); }

    Settings load() throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            try (InputStream in = ConfigFile.class.getResourceAsStream("/config.yml")) {
                if (in == null) throw new IOException("Missing bundled config.yml");
                Files.copy(in, file);
            }
        }
        CommentedConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
        if (root.node("schema-version").virtual()) {
            if (root.node("Messages-To-Hide-Filter").virtual()) throw new IOException("Unknown configuration format");
            root = migrate(root);
            Settings checked = parse(root);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Files.copy(file, file.resolveSibling("config-pre-paper-" + stamp + ".yml.bak"));
            Path temp = Files.createTempFile(file.getParent(), ".config-", ".tmp");
            try {
                YamlConfigurationLoader.builder().path(temp).build().save(root);
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
            return checked;
        }
        return parse(root);
    }

    private CommentedConfigurationNode migrate(CommentedConfigurationNode old) throws IOException {
        CommentedConfigurationNode n = CommentedConfigurationNode.root();
        n.node("schema-version").set(1);
        n.node("installation", "stage").set("BOOTSTRAP");
        n.node("archive", "enabled").set(bool(old.node("Log-Filtered-Messages"), false));
        ConfigurationNode filters = old.node("Messages-To-Hide-Filter");
        if (!filters.isList()) rejectKeys(filters, Set.of("contains", "regex"));
        List<String> contains = strings(filters.isList() ? filters : filters.node("contains"));
        List<String> regex = filters.isList() ? List.of() : strings(filters.node("regex"));
        n.node("rules").raw(List.of());
        int index = 0;
        for (String keyword : contains) {
            var r = n.node("rules").appendListNode();
            r.node("id").set("legacy-contains-" + ++index);
            r.node("message-contains-any").setList(String.class, List.of(keyword));
        }
        for (String pattern : regex) {
            var r = n.node("rules").appendListNode();
            r.node("id").set("legacy-regex-" + ++index);
            r.node("message-regex-any").setList(String.class, List.of(pattern));
        }
        n.node("upstream-config").from(old);
        return n;
    }

    private Settings parse(ConfigurationNode root) throws IOException {
        try {
            if (integer(root.node("schema-version"), -1) != 1) throw new IllegalArgumentException("Unsupported schema-version");
            rejectKeys(root, Set.of("schema-version", "installation", "rules", "archive", "upstream-config"));
            rejectKeys(root.node("installation"), Set.of("stage"));
            rejectKeys(root.node("archive"), Set.of("enabled", "queue-capacity", "max-file-mb", "max-files"));
            Settings.Stage stage = Settings.Stage.valueOf(root.node("installation", "stage").getString("BOOTSTRAP"));
            List<Rule> rules = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            if (!root.node("rules").isList()) throw new IllegalArgumentException("rules must be a list");
            for (ConfigurationNode r : root.node("rules").childrenList()) {
                rejectKeys(r, Set.of("id", "enabled", "phases", "logger-names", "levels", "message-equals",
                    "message-contains-any", "message-regex-any", "throwable-any", "action"));
                String id = r.node("id").getString("");
                if (!id.matches("[A-Za-z0-9_.-]+") || !ids.add(id)) throw new IllegalArgumentException("Invalid/duplicate rule id: " + id);
                if (!bool(r.node("enabled"), true)) continue;
                Set<Rule.Phase> phases = mapSet(strings(r.node("phases")), Rule.Phase::valueOf);
                Set<String> loggers = Set.copyOf(strings(r.node("logger-names")));
                Set<Level> levels = mapSet(strings(r.node("levels")), value -> {
                    Level l = Level.getLevel(value);
                    if (l == null) throw new IllegalArgumentException("Invalid log level: " + value);
                    return l;
                });
                String equals = r.node("message-equals").getString();
                if (!r.node("message-equals").virtual() && !(r.node("message-equals").raw() instanceof String))
                    throw new IllegalArgumentException("message-equals must be a string: " + id);
                if (equals != null && equals.isBlank()) throw new IllegalArgumentException("Blank message-equals: " + id);
                List<String> contains = strings(r.node("message-contains-any"));
                List<Pattern> regex = strings(r.node("message-regex-any")).stream().map(Pattern::compile).toList();
                List<Rule.Cause> causes = new ArrayList<>();
                if (!r.node("throwable-any").virtual() && !r.node("throwable-any").isList())
                    throw new IllegalArgumentException("throwable-any must be a list");
                for (ConfigurationNode c : r.node("throwable-any").childrenList()) {
                    rejectKeys(c, Set.of("class", "message-contains-any"));
                    String name = c.node("class").getString("");
                    if (name.isBlank()) throw new IllegalArgumentException("Missing throwable class: " + id);
                    causes.add(new Rule.Cause(name, strings(c.node("message-contains-any"))));
                }
                if (loggers.isEmpty() && levels.isEmpty() && equals == null && contains.isEmpty() && regex.isEmpty() && causes.isEmpty())
                    throw new IllegalArgumentException("Refusing unconstrained rule: " + id);
                rules.add(new Rule(id, phases, loggers, levels, equals, contains, regex, List.copyOf(causes),
                    Rule.Action.valueOf(r.node("action").getString("DROP"))));
            }
            int capacity = integer(root.node("archive", "queue-capacity"), 2048);
            int mb = integer(root.node("archive", "max-file-mb"), 16);
            int files = integer(root.node("archive", "max-files"), 4);
            if (capacity < 1 || capacity > 65536 || mb < 1 || mb > 1024 || files < 1 || files > 100)
                throw new IllegalArgumentException("Archive bounds: capacity 1..65536, MB 1..1024, files 1..100");
            return new Settings(stage, List.copyOf(rules), new Settings.Archive(
                bool(root.node("archive", "enabled"), false), capacity, mb * 1024L * 1024L, files));
        } catch (IllegalArgumentException e) { throw new IOException("Invalid config: " + e.getMessage(), e); }
    }

    private static List<String> strings(ConfigurationNode node) throws IOException {
        if (node.virtual()) return List.of();
        if (!node.isList()) throw new IOException(node.path() + " must be a list");
        List<String> result = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            if (!(child.raw() instanceof String s) || s.isBlank()) throw new IOException("Non-string/blank entry at " + child.path());
            result.add(s);
        }
        return List.copyOf(result);
    }
    private static boolean bool(ConfigurationNode node, boolean fallback) throws IOException {
        if (node.virtual()) return fallback;
        if (node.raw() instanceof Boolean value) return value;
        throw new IOException(node.path() + " must be true or false");
    }
    private static int integer(ConfigurationNode node, int fallback) throws IOException {
        if (node.virtual()) return fallback;
        Object value = node.raw();
        if (value instanceof Integer n) return n;
        if (value instanceof Long n && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) return n.intValue();
        throw new IOException(node.path() + " must be an integer");
    }
    private static void rejectKeys(ConfigurationNode node, Set<String> allowed) throws IOException {
        if (!node.virtual() && !node.isMap()) throw new IOException(node.path() + " must be a mapping");
        for (Object key : node.childrenMap().keySet()) {
            if (!allowed.contains(key.toString())) throw new IOException("Unknown config key: " + node.path() + "." + key);
        }
    }
    private static <T> Set<T> mapSet(List<String> list, Function<String, T> fn) {
        Set<T> set = new HashSet<>();
        list.forEach(s -> set.add(fn.apply(s)));
        return Set.copyOf(set);
    }
}
