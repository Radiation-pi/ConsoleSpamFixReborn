package link.star_dust.consolefix.paper;

import java.util.List;

record Settings(Stage stage, List<Rule> rules, Archive archive) {
    enum Stage { BOOTSTRAP, ON_LOAD, ON_ENABLE }
    record Archive(boolean enabled, int capacity, long maxBytes, int maxFiles) {}
}
