import java.lang.foreign.Linker;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes machine / OS / JDK information to a JSON file, so every JMH result file can be joined with the
 * environment it was produced on.
 *
 * <pre>java -cp tools/classes EnvInfo out/env-linux-x64.json</pre>
 */
public final class EnvInfo {
    private EnvInfo() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);

        String os = System.getProperty("os.name", "");
        String lower = os.toLowerCase(Locale.ROOT);

        var m = new LinkedHashMap<String, String>();
        m.put("timestamp", Instant.now().toString());
        m.put("platform", lower.contains("win") ? "windows" : lower.contains("mac") ? "macos" : "linux");
        m.put("osName", os);
        m.put("osVersion", System.getProperty("os.version", ""));
        m.put("osArch", System.getProperty("os.arch", ""));
        m.put("javaVersion", System.getProperty("java.version", ""));
        m.put("javaVendor", System.getProperty("java.vendor", ""));
        m.put("vmName", System.getProperty("java.vm.name", ""));
        m.put("vmVersion", System.getProperty("java.vm.version", ""));
        m.put("availableProcessors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        m.put("cpuModel", cpuModel());
        m.put("ramBytes", String.valueOf(totalMemory()));
        m.put("linkerClass", linkerClass());

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, toJson(m).getBytes(StandardCharsets.UTF_8));
    }

    private static String toJson(LinkedHashMap<String, String> m) {
        var sb = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : m.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": \"").append(esc(e.getValue())).append('"');
            if (++i < m.size()) sb.append(',');
            sb.append('\n');
        }
        return sb.append("}\n").toString();
    }

    private static String esc(String s) {
        var b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"'  -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int)c));
                    else b.append(c);
                }
            }
        }
        return b.toString();
    }

    private static long totalMemory() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
            return os.getTotalMemorySize();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String linkerClass() {
        try {
            return Linker.nativeLinker().getClass().getName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String cpuModel() {        String lower = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (lower.contains("win")) {
                String v = exec("powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).Name");
                if (v.isBlank()) v = exec("wmic", "cpu", "get", "name");
                return clean(v);
            } else if (lower.contains("mac")) {
                return clean(exec("sysctl", "-n", "machdep.cpu.brand_string"));
            } else {
                for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                    int idx = line.indexOf(':');
                    if (idx > 0) {
                        String key = line.substring(0, idx).trim();
                        if (key.equals("model name") || key.equals("Hardware") || key.equals("Processor")) {
                            return line.substring(idx + 1).trim();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String exec(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String clean(String s) {
        String v = s.replace("\r", "\n").trim();
        int nl = v.indexOf('\n');
        if (nl >= 0) v = v.substring(0, nl).trim();
        return v.isBlank() ? "unknown" : v;
    }
}
