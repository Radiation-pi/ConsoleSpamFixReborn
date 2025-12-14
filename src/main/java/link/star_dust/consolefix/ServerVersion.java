package link.star_dust.consolefix;

/**
 * Enum representing different Minecraft server versions and their engine types.
 */
public enum ServerVersion {
    /**
     * Minecraft 1.7.x - 1.8.x versions
     * Uses OldEngine, not 1.9+ compatible, not 1.13+ compatible
     */
    LEGACY_1_8(EngineType.OLD, false, false),

    /**
     * Minecraft 1.9.x - 1.12.x versions
     * Uses OldEngine, 1.9+ compatible, not 1.13+ compatible
     */
    LEGACY_1_9_TO_1_12(EngineType.OLD, true, false),

    /**
     * Minecraft 1.13+ versions
     * Uses NewEngine, 1.9+ compatible, 1.13+ compatible
     */
    MODERN_1_13_PLUS(EngineType.NEW, true, true);

    private final EngineType engineType;
    private final boolean is19Plus;
    private final boolean is13Plus;

    ServerVersion(EngineType engineType, boolean is19Plus, boolean is13Plus) {
        this.engineType = engineType;
        this.is19Plus = is19Plus;
        this.is13Plus = is13Plus;
    }

    /**
     * Get the engine type for this server version
     */
    public EngineType getEngineType() {
        return engineType;
    }

    /**
     * Check if this version is 1.9 or higher
     */
    public boolean is19Plus() {
        return is19Plus;
    }

    /**
     * Check if this version is 1.13 or higher
     */
    public boolean is13Plus() {
        return is13Plus;
    }

    /**
     * Check if this version uses the old engine
     */
    public boolean usesOldEngine() {
        return engineType == EngineType.OLD;
    }

    /**
     * Detect server version from version string
     * @param versionString The Minecraft version string (e.g., "1.8", "1.12.2", "1.20.1")
     * @return The corresponding ServerVersion enum
     */
    public static ServerVersion fromVersionString(String versionString) {
        // 1.7.x - 1.8.x
        if (versionString.matches("1\\.7\\.(10|9|5|2)") ||
            versionString.matches("1\\.8(\\.(8|4|3))?")) {
            return LEGACY_1_8;
        }

        // 1.9.x - 1.12.x
        if (versionString.matches("1\\.(9|10|11|12)(\\.\\d+)?")) {
            return LEGACY_1_9_TO_1_12;
        }

        // 1.13+ (default for anything else)
        return MODERN_1_13_PLUS;
    }

    /**
     * Engine type enumeration
     */
    public enum EngineType {
        OLD,
        NEW
    }
}

