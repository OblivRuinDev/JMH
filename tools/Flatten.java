import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flattens a JMH JSON result file plus its environment JSON into a tidy CSV, one row per benchmark.
 *
 * <pre>java -cp tools/classes Flatten out/env-linux-x64.json out/jmh-linux-x64.json out/results-linux-x64.csv</pre>
 *
 * <p>The produced CSV is meant to be imported into a spreadsheet. {@code backend} is {@code jni} or {@code ffm} and
 * {@code signature} is the {@code cType} (return type first, then the parameter types).</p>
 */
public final class Flatten {
    private Flatten() {}

    private static final String[] PERCENTILES = {"0.0", "25.0", "50.0", "75.0", "90.0", "95.0", "99.0", "99.9", "99.99", "100.0"};

    private static final String[] HEADER = {
        "platform", "arch", "osName", "osVersion", "jdk", "vmName", "cpuModel", "ramBytes",
        "backend", "signature", "ret", "params",
        "score", "error", "unit",
        "p0", "p25", "p50", "p75", "p90", "p95", "p99", "p99_9", "p99_99", "p100",
        "forks", "warmupIterations", "measurementIterations", "threads", "timestamp"
    };

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path envFile = Path.of(args[0]);
        Path jmhFile = Path.of(args[1]);
        Path outFile = Path.of(args[2]);
        Path callsFile = args.length > 3 ? Path.of(args[3]) : null;

        Map<String, Object> env = (Map<String, Object>)Json.parse(Files.readString(envFile, StandardCharsets.UTF_8));
        List<Object> results = (List<Object>)Json.parse(Files.readString(jmhFile, StandardCharsets.UTF_8));

        String platform = text(env, "platform");
        String arch = normalizeArch(text(env, "osArch"));
        String osName = text(env, "osName");
        String osVersion = text(env, "osVersion");
        String jdk = text(env, "javaVersion");
        String vmName = text(env, "vmName");
        String cpuModel = text(env, "cpuModel");
        String ramBytes = text(env, "ramBytes");
        String timestamp = text(env, "timestamp");

        var sb = new StringBuilder(1 << 20);
        sb.append(String.join(",", HEADER)).append('\n');

        // Raw per-measurement values, one row per benchmark: <method>,<v0>,<v1>,...
        var callsMethods = new ArrayList<String>();
        var callsValues = new ArrayList<List<String>>();
        int maxValues = 0;

        for (Object o : results) {
            Map<String, Object> r = (Map<String, Object>)o;

            String benchmark = text(r, "benchmark");
            String simple = benchmark.substring(benchmark.lastIndexOf('.') + 1);
            int us = simple.indexOf('_');
            String backend = us > 0 ? simple.substring(0, us) : simple;
            String signature = us > 0 ? simple.substring(us + 1) : "";
            String ret = signature.isEmpty() ? "" : signature.substring(0, 1);
            String params = signature.length() > 1 ? signature.substring(1) : "";

            Map<String, Object> pm = (Map<String, Object>)r.get("primaryMetric");
            Map<String, Object> pct = (Map<String, Object>)pm.get("scorePercentiles");

            var raw = (List<Object>)pm.get("rawData");
            var values = new ArrayList<String>();
            if (raw != null) {
                for (Object item : raw) {
                    if (item instanceof List<?> batch) {
                        for (Object v : batch) values.add(trim(((Number)v).doubleValue()));
                    } else if (item instanceof Number n) {
                        values.add(trim(n.doubleValue()));
                    }
                }
            }
            maxValues = Math.max(maxValues, values.size());
            callsMethods.add(simple);
            callsValues.add(values);

            var row = new ArrayList<String>(HEADER.length);
            row.add(platform);
            row.add(arch);
            row.add(osName);
            row.add(osVersion);
            row.add(jdk);
            row.add(vmName);
            row.add(cpuModel);
            row.add(ramBytes);
            row.add(backend);
            row.add(signature);
            row.add(ret);
            row.add(params);
            row.add(num(pm, "score"));
            row.add(num(pm, "scoreError"));
            row.add(text(pm, "scoreUnit"));
            for (String p : PERCENTILES) {
                row.add(pct == null || pct.get(p) == null ? "" : num(pct, p));
            }
            row.add(intText(r, "forks"));
            row.add(intText(r, "warmupIterations"));
            row.add(intText(r, "measurementIterations"));
            row.add(intText(r, "threads"));
            row.add(timestamp);

            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csv(row.get(i)));
            }
            sb.append('\n');
        }

        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);

        if (callsFile != null) {
            var cb = new StringBuilder(1 << 20);
            cb.append("method");
            for (int i = 0; i < maxValues; i++) cb.append(",v").append(i);
            cb.append('\n');
            for (int i = 0; i < callsMethods.size(); i++) {
                cb.append(csv(callsMethods.get(i)));
                for (String v : callsValues.get(i)) cb.append(',').append(v);
                cb.append('\n');
            }
            if (callsFile.getParent() != null) {
                Files.createDirectories(callsFile.getParent());
            }
            Files.writeString(callsFile, cb.toString(), StandardCharsets.UTF_8);
        }
    }

    private static String normalizeArch(String arch) {
        String a = arch.toLowerCase(Locale.ROOT);
        if (a.equals("amd64") || a.equals("x86_64") || a.equals("x64")) return "x64";
        if (a.equals("aarch64") || a.equals("arm64")) return "arm64";
        if (a.equals("x86") || a.equals("i386") || a.equals("i686")) return "x86";
        return arch;
    }

    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static String num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "";
        if (v instanceof Number n) return trim(n.doubleValue());
        try {
            return trim(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return String.valueOf(v);
        }
    }

    private static String intText(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return "";
        if (v instanceof Number n) return String.valueOf(n.longValue());
        return String.valueOf(v);
    }

    private static String trim(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf((long)d);
        return String.valueOf(d);
    }

    /** RFC4180-ish escaping. */
    private static String csv(String s) {
        if (s == null) return "";
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!quote) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** A tiny recursive-descent JSON parser (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            return j.value();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> obj();
                case '[' -> arr();
                case '"' -> str();
                case 't' -> { i += 4; yield Boolean.TRUE; }
                case 'f' -> { i += 5; yield Boolean.FALSE; }
                case 'n' -> { i += 4; yield null; }
                default -> num();
            };
        }

        private Map<String, Object> obj() {
            var m = new LinkedHashMap<String, Object>();
            i++; // {
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                i++; // :
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') break;
            }
            return m;
        }

        private List<Object> arr() {
            var l = new ArrayList<Object>();
            i++; // [
            ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value());
                ws();
                if (s.charAt(i++) == ']') break;
            }
            return l;
        }

        private String str() {
            ws();
            i++; // "
            var b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"'  -> b.append('"');
                        case '\\' -> b.append('\\');
                        case '/'  -> b.append('/');
                        case 'b'  -> b.append('\b');
                        case 'f'  -> b.append('\f');
                        case 'n'  -> b.append('\n');
                        case 'r'  -> b.append('\r');
                        case 't'  -> b.append('\t');
                        case 'u'  -> { b.append((char)Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default   -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private Object num() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
