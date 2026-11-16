package fr.redkingdom.launcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Logique du launcher : versions, telechargements, construction de la commande Java. */
final class Core {
    // URLs (modifiables sans recompiler avec -Drl.manifest=... -Drl.assets=... -Drl.libs=...)
    static String MANIFEST = System.getProperty("rl.manifest", "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    static String ASSET_BASE = System.getProperty("rl.assets", "https://resources.download.minecraft.net/");
    static String LIB_BASE = System.getProperty("rl.libs", "https://libraries.minecraft.net/");
    static final String OS = osName();
    static final String ARCH = archName();

    final Path root;
    private final HttpClient http;

    record Entry(String id, String type, String url) {
        @Override public String toString() { return id + ("local".equals(type) ? "  (installée)" : ""); }
    }

    record Task(String url, Path path, String sha1, long size) {}

    static final class Options {
        String user;
        int memoryMb;
        String java;
        String server;
        boolean fullscreen;
    }

    interface Progress { void update(String text, double fraction); }

    Core(Path root) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.root = root;
    }

    // ------------------------------------------------------------------ versions

    List<Entry> remoteVersions() throws IOException {
        Map<String, Object> m = obj(Json.parse(getString(MANIFEST)));
        List<Entry> out = new ArrayList<>();
        for (Object o : list(m.get("versions"))) {
            Map<String, Object> v = obj(o);
            out.add(new Entry(str(v.get("id")), str(v.get("type")), str(v.get("url"))));
        }
        return out;
    }

    List<Entry> localVersions() {
        List<Entry> out = new ArrayList<>();
        Path dir = root.resolve("versions");
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (Files.exists(p.resolve(name + ".json"))) out.add(new Entry(name, "local", null));
            }
        } catch (IOException e) {
            // ignore
        }
        out.sort(Comparator.comparing(Entry::id));
        return out;
    }

    private Map<String, Object> resolve(String id, Map<String, String> urls) throws IOException {
        Path file = root.resolve("versions/" + id + "/" + id + ".json");
        if (!Files.exists(file)) {
            String url = urls.get(id);
            if (url == null) throw new IOException("Version introuvable : " + id);
            download(new Task(url, file, null, -1));
        }
        Map<String, Object> meta = obj(Json.parse(Files.readString(file)));
        String parent = meta.get("inheritsFrom") instanceof String s ? s : null;
        if (parent != null) {
            meta = merge(resolve(parent, urls), meta, parent);
        } else {
            meta.put("_jarId", meta.get("jar") instanceof String s ? s : id);
        }
        return meta;
    }

    private Map<String, Object> merge(Map<String, Object> base, Map<String, Object> child, String parentId) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : child.entrySet()) {
            String k = e.getKey();
            if (k.equals("libraries")) {
                List<Object> libs = new ArrayList<>(list(e.getValue()));
                libs.addAll(list(base.get("libraries")));
                out.put(k, libs);
            } else if (k.equals("arguments")) {
                Map<String, Object> ba = base.get("arguments") instanceof Map ? obj(base.get("arguments")) : new LinkedHashMap<>();
                Map<String, Object> ca = obj(e.getValue());
                Map<String, Object> merged = new LinkedHashMap<>();
                for (String part : new String[]{"jvm", "game"}) {
                    List<Object> l = new ArrayList<>(list(ba.get(part)));
                    l.addAll(list(ca.get(part)));
                    merged.put(part, l);
                }
                out.put(k, merged);
            } else {
                out.put(k, e.getValue());
            }
        }
        out.put("_jarId", child.get("jar") instanceof String s ? s : base.get("_jarId"));
        return out;
    }

    // ------------------------------------------------------------------ lancement

    Process launch(Entry entry, List<Entry> all, Options opts, Progress progress) throws Exception {
        Map<String, String> urls = new HashMap<>();
        for (Entry e : all) if (e.url() != null) urls.put(e.id(), e.url());
        if (entry.url() != null) urls.put(entry.id(), entry.url());

        progress.update("Lecture de la version " + entry.id() + "...", 0.02);
        Map<String, Object> meta = resolve(entry.id(), urls);
        String jarId = str(meta.get("_jarId"));

        Set<String> features = new HashSet<>();
        String server = opts.server == null ? "" : opts.server.trim();
        boolean quickPlay = Json.write(meta).contains("has_quick_plays_support");
        if (!server.isEmpty() && quickPlay) features.add("has_quick_plays_support");

        List<Task> tasks = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        List<Object[]> natives = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean x64 = !ARCH.equals("x86");

        for (Object o : list(meta.get("libraries"))) {
            Map<String, Object> lib = obj(o);
            if (!ruleOk(lib.get("rules"), features)) continue;
            String name = str(lib.get("name"));
            String[] p = name.split(":");
            String key = p[0] + ":" + p[1] + (p.length > 3 ? ":" + p[3] : "");
            Map<String, Object> downloads = lib.get("downloads") instanceof Map ? obj(lib.get("downloads")) : null;

            // natives (anciennes versions)
            String nativeKey = null;
            if (lib.get("natives") instanceof Map) {
                if (obj(lib.get("natives")).get(OS) instanceof String s) {
                    nativeKey = s.replace("${arch}", x64 ? "64" : "32");
                }
            }
            if (nativeKey != null && downloads != null && downloads.get("classifiers") instanceof Map) {
                Map<String, Object> cl = obj(downloads.get("classifiers"));
                if (cl.get(nativeKey) instanceof Map) {
                    Task t = taskOf(obj(cl.get(nativeKey)), "libraries");
                    tasks.add(t);
                    List<String> exclude = new ArrayList<>();
                    if (lib.get("extract") instanceof Map) {
                        for (Object x : list(obj(lib.get("extract")).get("exclude"))) exclude.add(str(x));
                    }
                    natives.add(new Object[]{t.path(), exclude});
                }
            }

            Task t = null;
            if (downloads != null && downloads.get("artifact") instanceof Map) {
                t = taskOf(obj(downloads.get("artifact")), "libraries");
            } else if (downloads == null) {
                // librairie sans bloc "downloads" : chemin Maven classique
                String rel = p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2]
                        + (p.length > 3 ? "-" + p[3] : "") + ".jar";
                String base = lib.get("url") instanceof String s ? s : LIB_BASE;
                if (!base.endsWith("/")) base = base + "/";
                t = new Task(base + rel, root.resolve("libraries/" + rel),
                        lib.get("sha1") instanceof String s ? s : null,
                        lib.get("size") instanceof Number n ? n.longValue() : -1);
            }
            if (t != null && seen.add(key)) {
                tasks.add(t);
                classpath.add(t.path());
            }
        }

        // jar du jeu
        Path jar = root.resolve("versions/" + jarId + "/" + jarId + ".jar");
        if (meta.get("downloads") instanceof Map && obj(meta.get("downloads")).get("client") instanceof Map) {
            Map<String, Object> c = obj(obj(meta.get("downloads")).get("client"));
            tasks.add(new Task(str(c.get("url")), jar, str(c.get("sha1")), num(c.get("size"))));
        } else if (!Files.exists(jar)) {
            throw new IOException("Jar du jeu introuvable : " + jar);
        }
        classpath.add(jar);

        // config de logs
        Path logPath = null;
        String logArg = null;
        if (meta.get("logging") instanceof Map && obj(meta.get("logging")).get("client") instanceof Map) {
            Map<String, Object> lc = obj(obj(meta.get("logging")).get("client"));
            if (lc.get("file") instanceof Map) {
                Map<String, Object> f = obj(lc.get("file"));
                logPath = root.resolve("assets/log_configs/" + str(f.get("id")));
                tasks.add(new Task(str(f.get("url")), logPath, str(f.get("sha1")), num(f.get("size"))));
                logArg = lc.get("argument") instanceof String s ? s : null;
            }
        }

        // assets
        String assetId = "legacy";
        boolean mapToResources = false;
        List<Task> assetTasks = new ArrayList<>();
        Map<String, String> virtual = new LinkedHashMap<>();
        if (meta.get("assetIndex") instanceof Map) {
            Map<String, Object> ai = obj(meta.get("assetIndex"));
            assetId = str(ai.get("id"));
            Path idxPath = root.resolve("assets/indexes/" + assetId + ".json");
            download(new Task(str(ai.get("url")), idxPath, str(ai.get("sha1")), -1));
            Map<String, Object> idx = obj(Json.parse(Files.readString(idxPath)));
            mapToResources = Boolean.TRUE.equals(idx.get("virtual")) || Boolean.TRUE.equals(idx.get("map_to_resources"));
            for (Map.Entry<String, Object> e : obj(idx.get("objects")).entrySet()) {
                Map<String, Object> ob = obj(e.getValue());
                String hash = str(ob.get("hash"));
                String pre = hash.substring(0, 2);
                assetTasks.add(new Task(ASSET_BASE + pre + "/" + hash,
                        root.resolve("assets/objects/" + pre + "/" + hash), null, num(ob.get("size"))));
                virtual.put(e.getKey(), pre + "/" + hash);
            }
        }
        tasks.addAll(assetTasks);

        progress.update("Telechargement (" + tasks.size() + " fichiers)...", 0.05);
        runDownloads(tasks, progress);

        // natives
        Path nativesDir = root.resolve("versions/" + entry.id() + "/natives");
        Files.createDirectories(nativesDir);
        for (Object[] n : natives) {
            @SuppressWarnings("unchecked") List<String> excl = (List<String>) n[1];
            extract((Path) n[0], nativesDir, excl);
        }

        // assets "virtual" (anciennes versions)
        Path assetsDir = root.resolve("assets");
        if (mapToResources) {
            assetsDir = root.resolve("assets/virtual/" + assetId);
            for (Map.Entry<String, String> e : virtual.entrySet()) {
                Path target = assetsDir.resolve(e.getKey());
                if (!Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(root.resolve("assets/objects/" + e.getValue()), target);
                }
            }
        }

        // Java adapte a la version (ex: Java 8 pour les vieilles versions), telecharge automatiquement
        Options runOpts = opts;
        if (opts.java == null || opts.java.isBlank()) {
            String javaExe = ensureJava(requiredJava(meta), progress);
            if (javaExe != null) {
                runOpts = new Options();
                runOpts.user = opts.user;
                runOpts.memoryMb = opts.memoryMb;
                runOpts.server = opts.server;
                runOpts.fullscreen = opts.fullscreen;
                runOpts.java = javaExe;
            }
        }

        progress.update("Lancement...", 0.98);
        List<String> cmd = buildCommand(meta, entry.id(), runOpts, features, server, quickPlay,
                classpath, nativesDir, assetsDir, assetId, logPath, logArg);

        Files.createDirectories(root.resolve("logs"));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(root.resolve("logs/dernier-lancement.log").toFile());
        return pb.start();
    }

    /** Version de Java demandee par le jeu (8 si non precisee : versions anciennes). */
    private static int requiredJava(Map<String, Object> meta) {
        if (meta.get("javaVersion") instanceof Map) {
            long n = num(obj(meta.get("javaVersion")).get("majorVersion"));
            if (n > 0) return (int) n;
        }
        return 8;
    }

    /** Renvoie le chemin d'un Java adapte, ou null si le Java actuel convient. Telecharge Temurin si besoin. */
    private String ensureJava(int need, Progress progress) throws Exception {
        int current = Runtime.version().feature();
        if (need <= 8 ? current == 8 : current >= need) return null;

        Path dir = root.resolve("runtime/java" + need);
        Path exe = findJava(dir);
        if (exe != null) return exe.toString();

        progress.update("Telechargement de Java " + need + "...", 0.03);
        String os = OS.equals("windows") ? "windows" : OS.equals("osx") ? "mac" : "linux";
        String arch = ARCH.equals("x86") ? "x32" : ARCH.equals("arm64") ? "aarch64" : "x64";
        String url = "https://api.adoptium.net/v3/binary/latest/" + need + "/ga/" + os + "/" + arch + "/jre/hotspot/normal/eclipse";
        boolean zip = OS.equals("windows");
        Path archive = root.resolve("runtime/java" + need + (zip ? ".zip" : ".tar.gz"));
        download(new Task(url, archive, null, -1));

        progress.update("Installation de Java " + need + "...", 0.04);
        Files.createDirectories(dir);
        try {
            if (zip) {
                extract(archive, dir, List.of());
            } else {
                Process pr = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dir.toString()).start();
                if (pr.waitFor() != 0) throw new IOException("Extraction de Java impossible");
            }
        } catch (IOException e) {
            Files.deleteIfExists(archive);
            throw e;
        }
        Files.deleteIfExists(archive);
        exe = findJava(dir);
        if (exe == null) throw new IOException("Java " + need + " introuvable apres installation");
        return exe.toString();
    }

    private static Path findJava(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        String name = OS.equals("windows") ? "javaw.exe" : "java";
        try (java.util.stream.Stream<Path> w = Files.walk(dir, 6)) {
            return w.filter(f -> f.getFileName().toString().equals(name)
                            && f.getParent() != null && f.getParent().getFileName().toString().equals("bin"))
                    .findFirst().orElse(null);
        }
    }

    List<String> buildCommand(Map<String, Object> meta, String versionName, Options o, Set<String> features,
                              String server, boolean quickPlay, List<Path> cp, Path nativesDir, Path assetsDir,
                              String assetId, Path logPath, String logArg) {
        String user = (o.user == null || o.user.isBlank()) ? "Joueur" : o.user.trim();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + user).getBytes(StandardCharsets.UTF_8));

        StringBuilder cpStr = new StringBuilder();
        for (Path p : cp) {
            if (cpStr.length() > 0) cpStr.append(File.pathSeparator);
            cpStr.append(p.toAbsolutePath());
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("auth_player_name", user);
        vars.put("version_name", versionName);
        vars.put("game_directory", root.toAbsolutePath().toString());
        vars.put("assets_root", assetsDir.toAbsolutePath().toString());
        vars.put("game_assets", assetsDir.toAbsolutePath().toString());
        vars.put("assets_index_name", assetId);
        vars.put("auth_uuid", uuid.toString().replace("-", ""));
        vars.put("auth_access_token", "0");
        vars.put("auth_session", "0");
        vars.put("clientid", "0");
        vars.put("auth_xuid", "0");
        vars.put("user_type", "legacy");
        vars.put("user_properties", "{}");
        vars.put("version_type", meta.get("type") instanceof String s ? s : "release");
        vars.put("natives_directory", nativesDir.toAbsolutePath().toString());
        vars.put("launcher_name", "Despylauncher");
        vars.put("launcher_version", "1.0");
        vars.put("classpath", cpStr.toString());
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", root.resolve("libraries").toAbsolutePath().toString());
        if (logPath != null) vars.put("path", logPath.toAbsolutePath().toString());
        if (!server.isEmpty() && quickPlay) vars.put("quickPlayMultiplayer", server);

        List<String> cmd = new ArrayList<>();
        cmd.add((o.java == null || o.java.isBlank())
                ? Path.of(System.getProperty("java.home"), "bin", OS.equals("windows") ? "javaw.exe" : "java").toString()
                : o.java.trim());
        cmd.add("-Xmx" + Math.max(512, o.memoryMb) + "M");

        Object args = meta.get("arguments");
        if (args instanceof Map) {
            cmd.addAll(expand(obj(args).get("jvm"), vars, features));
        } else {
            cmd.add("-Djava.library.path=" + vars.get("natives_directory"));
            cmd.add("-cp");
            cmd.add(vars.get("classpath"));
        }
        if (logArg != null) cmd.add(sub(logArg, vars));
        cmd.add(str(meta.get("mainClass")));
        if (args instanceof Map) {
            cmd.addAll(expand(obj(args).get("game"), vars, features));
        } else if (meta.get("minecraftArguments") instanceof String s) {
            for (String a : s.trim().split("\\s+")) cmd.add(sub(a, vars));
        }

        boolean positional = meta.get("minecraftArguments") instanceof String ma && !ma.contains("--");
        if (o.fullscreen && !positional) cmd.add("--fullscreen");

        // connexion auto au serveur (anciennes versions sans quick play)
        if (!server.isEmpty() && !quickPlay && !positional) {
            String host = server;
            String port = "25565";
            int idx = server.lastIndexOf(':');
            if (idx > 0 && server.indexOf(':') == idx) {
                host = server.substring(0, idx);
                port = server.substring(idx + 1);
            }
            cmd.add("--server");
            cmd.add(host);
            cmd.add("--port");
            cmd.add(port);
        }
        return cmd;
    }

    // ------------------------------------------------------------------ regles / arguments

    static boolean ruleOk(Object rules, Set<String> features) {
        if (rules == null) return true;
        boolean allowed = false;
        for (Object r : list(rules)) {
            Map<String, Object> m = obj(r);
            boolean ok = true;
            if (m.get("os") instanceof Map) {
                Map<String, Object> os = obj(m.get("os"));
                if (os.get("name") != null && !OS.equals(os.get("name"))) ok = false;
                if (os.get("arch") != null && !ARCH.equals(os.get("arch"))) ok = false;
            }
            if (m.get("features") instanceof Map) {
                for (Map.Entry<String, Object> f : obj(m.get("features")).entrySet()) {
                    if (features.contains(f.getKey()) != Boolean.TRUE.equals(f.getValue())) ok = false;
                }
            }
            if (ok) allowed = "allow".equals(m.get("action"));
        }
        return allowed;
    }

    private static List<String> expand(Object arg, Map<String, String> vars, Set<String> features) {
        List<String> out = new ArrayList<>();
        for (Object a : list(arg)) {
            if (a instanceof String s) {
                out.add(sub(s, vars));
            } else if (a instanceof Map) {
                Map<String, Object> m = obj(a);
                if (!ruleOk(m.get("rules"), features)) continue;
                Object v = m.get("value");
                if (v instanceof String s) {
                    out.add(sub(s, vars));
                } else {
                    for (Object x : list(v)) out.add(sub(str(x), vars));
                }
            }
        }
        return out;
    }

    private static String sub(String s, Map<String, String> vars) {
        for (Map.Entry<String, String> e : vars.entrySet()) s = s.replace("${" + e.getKey() + "}", e.getValue());
        return s;
    }

    private Task taskOf(Map<String, Object> m, String base) {
        String path = m.get("path") instanceof String s ? s
                : str(m.get("url")).substring(str(m.get("url")).lastIndexOf('/') + 1);
        return new Task(str(m.get("url")), root.resolve(base + "/" + path),
                m.get("sha1") instanceof String s ? s : null, num(m.get("size")));
    }

    // ------------------------------------------------------------------ telechargements

    private void runDownloads(List<Task> all, Progress progress) throws Exception {
        List<Task> todo = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Task t : all) if (seen.add(t.path()) && !isGood(t)) todo.add(t);
        if (todo.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(12);
        AtomicInteger counter = new AtomicInteger();
        List<Future<Object>> futures = new ArrayList<>();
        try {
            for (Task t : todo) {
                futures.add(pool.submit(() -> {
                    download(t);
                    int n = counter.incrementAndGet();
                    if (n % 20 == 0 || n == todo.size()) {
                        progress.update("Telechargement " + n + "/" + todo.size(), 0.05 + 0.9 * n / todo.size());
                    }
                    return null;
                }));
            }
            for (Future<Object> f : futures) f.get();
        } catch (ExecutionException e) {
            for (Future<Object> f : futures) f.cancel(true);
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }

    private boolean isGood(Task t) {
        try {
            if (!Files.exists(t.path())) return false;
            if (t.sha1() != null) return sha1(t.path()).equalsIgnoreCase(t.sha1());
            return t.size() < 0 || Files.size(t.path()) == t.size();
        } catch (IOException e) {
            return false;
        }
    }

    void download(Task t) throws IOException {
        if (isGood(t)) return;
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.createDirectories(t.path().getParent());
                Path tmp = t.path().resolveSibling(t.path().getFileName() + ".part");
                HttpResponse<InputStream> resp = http.send(
                        HttpRequest.newBuilder(URI.create(t.url())).timeout(Duration.ofMinutes(2)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " pour " + t.url());
                try (InputStream in = resp.body()) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                if (t.sha1() != null && !sha1(tmp).equalsIgnoreCase(t.sha1())) {
                    throw new IOException("Fichier corrompu : " + t.url());
                }
                Files.move(tmp, t.path(), StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrompu");
            }
        }
        throw last;
    }

    private String getString(String url) throws IOException {
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu");
        }
    }

    private static String sha1(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void extract(Path zip, Path dest, List<String> exclude) throws IOException {
        try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF")) continue;
                boolean skip = false;
                for (String x : exclude) if (name.startsWith(x)) skip = true;
                Path target = dest.resolve(name).normalize();
                if (skip || !target.startsWith(dest.normalize())) continue;
                Files.createDirectories(target.getParent());
                Files.copy(z, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ------------------------------------------------------------------ utilitaires JSON

    static Map<String, Object> obj(Object o) {
        if (o instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            return m;
        }
        throw new IllegalArgumentException("objet JSON attendu");
    }

    static List<Object> list(Object o) {
        if (o instanceof List) {
            @SuppressWarnings("unchecked") List<Object> l = (List<Object>) o;
            return l;
        }
        return List.of();
    }

    static String str(Object o) {
        if (o instanceof String s) return s;
        throw new IllegalArgumentException("texte JSON attendu");
    }

    static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    private static String osName() {
        String s = System.getProperty("os.name", "").toLowerCase();
        if (s.contains("win")) return "windows";
        if (s.contains("mac")) return "osx";
        return "linux";
    }

    private static String archName() {
        String s = System.getProperty("os.arch", "").toLowerCase();
        if (s.equals("x86") || s.equals("i386") || s.equals("i686")) return "x86";
        if (s.contains("aarch64") || s.contains("arm64")) return "arm64";
        return "x64";
    }
}
