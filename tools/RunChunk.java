import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.CommandLineOptions;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs one chunk of the benchmark set.
 *
 * <p>The chunk is selected by the <b>signature</b> (the {@code cType}), so both the {@code ffm_<sig>} and the
 * {@code jni_<sig>} benchmark always fall in the same chunk and therefore run on the same machine. This keeps the
 * ffm/jni comparison free of cross-machine bias.</p>
 *
 * <pre>RunChunk &lt;chunk&gt; &lt;chunks&gt; &lt;jmh args...&gt;
 * RunChunk --list &lt;chunk&gt; &lt;chunks&gt;          # print the selection and exit</pre>
 */
public final class RunChunk {
    private RunChunk() {}

    /**
     * Anchor signatures: these are included in every chunk, so the chunks can be compared / normalised against
     * each other (each chunk runs on a different machine). 10 signatures = 20 anchor benchmarks.
     */
    private static final int ANCHOR_SIGS = 10;

    public static void main(String[] args) throws Exception {
        boolean list = args.length > 0 && args[0].equals("--list");
        int base = list ? 1 : 0;
        int chunk = Integer.parseInt(args[base]);
        int chunks = Integer.parseInt(args[base + 1]);
        String[] rest = Arrays.copyOfRange(args, base + 2, args.length);

        List<String> names = benchmarkNames();

        // The first ANCHOR_SIGS distinct (sorted) signatures are present in every chunk.
        TreeSet<String> allSigs = new TreeSet<>();
        for (String full : names) allSigs.add(sigOf(full));
        Set<String> anchors = new HashSet<>();
        Iterator<String> it = allSigs.iterator();
        for (int i = 0; i < ANCHOR_SIGS && it.hasNext(); i++) anchors.add(it.next());

        List<String> selected = new ArrayList<>();
        for (String full : names) {
            String sig = sigOf(full);
            if (anchors.contains(sig) || Math.floorMod(sig.hashCode(), chunks) == chunk) {
                selected.add(full);
            }
        }
        System.out.println("RunChunk: chunk " + chunk + "/" + chunks + " -> " + selected.size()
            + " benchmarks (" + anchors.size() + " anchor signatures: " + anchors + ")");
        if (list) {
            selected.stream().limit(6).forEach(n -> System.out.println("  " + n));
            return;
        }
        if (selected.isEmpty()) {
            System.err.println("RunChunk: empty chunk");
            System.exit(3);
        }

        StringBuilder re = new StringBuilder("^(?:");
        boolean first = true;
        for (String n : selected) {
            if (!first) re.append('|');
            first = false;
            re.append(Pattern.quote(n));
        }
        re.append(")$");

        Options opt = new OptionsBuilder()
            .parent(new CommandLineOptions(rest))
            .include(re.toString())
            .build();
        new Runner(opt).run();
    }

    /** The signature (cType) of a benchmark, e.g. {@code ffm_BBP} -> {@code BBP}. */
    private static String sigOf(String full) {
        String method = full.substring(full.lastIndexOf('.') + 1);
        return method.substring(method.indexOf('_') + 1);
    }

    /** The full benchmark names, parsed out of {@code META-INF/BenchmarkList}. */
    private static List<String> benchmarkNames() throws Exception {
        List<String> out = new ArrayList<>();
        Enumeration<URL> urls = Thread.currentThread().getContextClassLoader().getResources("META-INF/BenchmarkList");
        Pattern p = Pattern.compile("S \\d+ (\\S+)");
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(url.openStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("JMH")) continue;
                    Matcher m = p.matcher(line);
                    List<String> parts = new ArrayList<>();
                    while (m.find()) parts.add(m.group(1));
                    if (parts.size() >= 3) {
                        out.add(parts.get(0) + "." + parts.get(2));   // <benchmark class>.<method>
                    }
                }
            }
        }
        return out;
    }
}
