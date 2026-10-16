package audiogear.testing;

import audiogear.importers.AutoEqClient;
import audiogear.net.Http;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Serves a tiny AutoEq repository (reviewer folders, measurement CSVs and the Harman targets) from a StubServer. */
public final class AutoEqStub {
    private AutoEqStub() {}

    public static final String CSV = "frequency,raw\n20,-6\n1000,0\n20000,-10\n";
    private static final String API = "/repos/jaakkopasanen/AutoEq/git/trees/";
    private static final String RAW = "/jaakkopasanen/AutoEq/master/";

    /** sources maps a reviewer folder to its files, as paths below it, e.g. "data/over-ear/Sennheiser HD 600.csv". */
    public static void serve(StubServer stub, Map<String, List<String>> sources) {
        stub.on(API + "master", "{\"tree\":[{\"path\":\"measurements\",\"type\":\"tree\",\"sha\":\"m\"}]}");
        StringBuilder folders = new StringBuilder();
        for (var e : new TreeMap<>(sources).entrySet()) {
            if (!folders.isEmpty()) folders.append(',');
            folders.append("{\"path\":\"").append(e.getKey()).append("\",\"type\":\"tree\",\"sha\":\"src-").append(e.getKey()).append("\"}");
            StringBuilder blobs = new StringBuilder();
            for (String file : e.getValue()) {
                if (!blobs.isEmpty()) blobs.append(',');
                blobs.append("{\"path\":\"").append(file).append("\",\"type\":\"blob\"}");
                stub.on(RAW + "measurements/" + e.getKey() + "/" + file.replace(" ", "%20"), CSV);
            }
            stub.on(API + "src-" + e.getKey() + "?recursive=1", "{\"tree\":[" + blobs + "]}");
        }
        stub.on(API + "m", "{\"tree\":[" + folders + "]}");
        stub.on(RAW + "targets/Harman%20over-ear%202018.csv", "frequency,raw\n20,3\n1000,0\n20000,-8\n");
        stub.on(RAW + "targets/Harman%20in-ear%202019.csv", "frequency,raw\n20,8\n1000,0\n20000,-6\n");
    }

    public static AutoEqClient client(StubServer stub, Path cacheDir) {
        return new AutoEqClient(new Http(), stub.base(), stub.base(), () -> null, cacheDir);
    }
}
