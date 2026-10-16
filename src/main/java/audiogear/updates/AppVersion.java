package audiogear.updates;

/** The running app's version: set by the jpackage launcher, else the jar manifest, else "dev". */
public final class AppVersion {
    private AppVersion() {}

    public static String current() {
        String v = System.getProperty("jpackage.app-version");
        if (v != null && !v.isBlank()) return v.strip();
        v = AppVersion.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v.strip();
    }
}
