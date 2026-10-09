package org.zyrexchain.desktop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bouncycastle.crypto.digests.Blake2bDigest;

/** Owns one full testnet node, its private data and its exact child process. */
public final class NodeManager implements AutoCloseable {
    private final Path home;
    private final Path javaPath;
    private final Path nodeJar;
    private final FileChannel lockChannel;
    private final FileLock instanceLock;
    private final String apiKey;
    private volatile Process process;
    private volatile NodeApi api;
    private volatile int apiPort;
    private volatile int p2pPort;
    private boolean closed;

    public NodeManager(Path dataHome, Path javaPath, Path nodeJar) throws IOException {
        this.home = dataHome.toAbsolutePath().normalize();
        this.javaPath = javaPath.toAbsolutePath().normalize();
        this.nodeJar = nodeJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(this.javaPath) || !Files.isRegularFile(this.nodeJar)) {
            throw new IOException("The bundled node or Java runtime is missing");
        }
        privateDirectory(home);
        Path lockPath = home.resolve("desktop.lock");
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            createPrivateFile(lockPath, new byte[0]);
        }
        requirePrivateFile(lockPath);
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException("Zyrex Desktop is already using this data directory");
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (acquired == null) {
            channel.close();
            throw new IOException("Zyrex Desktop is already using this data directory");
        }
        this.lockChannel = channel;
        this.instanceLock = acquired;
        try {
            privateDirectory(home.resolve("node"));
            privateDirectory(home.resolve("node/wallet"));
            privateDirectory(home.resolve("node/wallet/keystore"));
            privateDirectory(home.resolve("tmp"));
            this.apiKey = readOrCreateSettings();
            recoverPreviousNode();
        } catch (IOException | RuntimeException e) {
            acquired.release();
            channel.close();
            throw e;
        }
    }

    public static Path defaultDataHome() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            Path base = appData == null || appData.isEmpty()
                    ? Paths.get(System.getProperty("user.home"), "AppData", "Local") : Paths.get(appData);
            return base.resolve("ZyrexChain").resolve("testnet");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg == null || xdg.isEmpty() || !Paths.get(xdg).isAbsolute()
                ? Paths.get(System.getProperty("user.home"), ".local", "share") : Paths.get(xdg);
        return base.resolve("zyrex").resolve("testnet");
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IOException("The desktop node manager is closed");
        }
        if (isRunning()) {
            return;
        }
        try (ServerSocket apiReservation = freeSocket(); ServerSocket p2pReservation = freeSocket()) {
            apiPort = apiReservation.getLocalPort();
            p2pPort = p2pReservation.getLocalPort();
        }
        writePrivate(home.resolve("node.conf"), config().getBytes(StandardCharsets.UTF_8));
        writePrivate(home.resolve("logback.xml"), logging().getBytes(StandardCharsets.UTF_8));
        Path launcherLog = home.resolve("launcher.log");
        writePrivate(launcherLog, new byte[0], false);
        ProcessBuilder builder;
        if (windows()) {
            builder = windowsBootstrap();
        } else {
            builder = new ProcessBuilder(javaPath.toString(), "-Xms64m", "-Xmx1024m", "-XX:ActiveProcessorCount=2",
                    "-Djava.io.tmpdir=" + home.resolve("tmp"), "-Dlogback.configurationFile=" + home.resolve("logback.xml"),
                    "-jar", nodeJar.toString(), "--testnet", "-c", home.resolve("node.conf").toString());
            builder.directory(home.toFile());
        }
        builder.redirectErrorStream(true).redirectOutput(launcherLog.toFile());
        for (String variable : Arrays.asList("DATADIR", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            builder.environment().remove(variable);
        }
        process = builder.start();
        api = new NodeApi(apiPort, apiKey, this::isRunning, nodeName());
        try {
            Instant started = process.info().startInstant().orElseThrow(() -> new IOException("Cannot identify the managed node process"));
            writePrivate(home.resolve("node-process.json"), Json.stringify(Json.object("pid", process.pid(), "started", started.toString(),
                    "apiPort", apiPort, "p2pPort", p2pPort, "name", nodeName())).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private ProcessBuilder windowsBootstrap() throws IOException {
        Path source;
        try {
            source = Path.of(NodeBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        } catch (java.net.URISyntaxException | RuntimeException e) {
            throw new IOException("The managed node bootstrap could not locate the desktop application");
        }
        Path directory = source.getParent();
        String classpath = source.getFileName().toString();
        if (!classpath.chars().allMatch(value -> value < 128)) {
            throw new IOException("The desktop application archive must have its original ASCII filename");
        }
        try {
            String relativeNode = directory.relativize(nodeJar).toString();
            if (relativeNode.chars().allMatch(value -> value < 128)) {
                classpath += java.io.File.pathSeparator + relativeNode;
            }
        } catch (IllegalArgumentException ignored) {
            // External test fixtures may be on a different drive; the bootstrap loads them by URL.
        }
        List<String> command = new ArrayList<>(Arrays.asList(javaPath.toString(), "-Xms64m", "-Xmx1024m",
                "-XX:ActiveProcessorCount=2", "-cp", classpath, NodeBootstrap.class.getName(),
                encodedPath(home), encodedPath(nodeJar)));
        return new ProcessBuilder(command).directory(directory.toFile());
    }

    private static String encodedPath(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
    }

    public NodeApi api() throws IOException {
        NodeApi result = api;
        if (result == null) {
            throw new IOException("The managed node has not started");
        }
        return result;
    }

    public boolean isRunning() {
        Process child = process;
        return child != null && child.toHandle().isAlive();
    }

    public Path dataHome() { return home; }
    public Path logPath() { return home.resolve("node.log"); }
    public int apiPort() { return apiPort; }
    public int p2pPort() { return p2pPort; }

    public void awaitReady(Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        IOException latest = null;
        while (System.nanoTime() < deadline) {
            if (!isRunning()) {
                throw new IOException("The bundled node stopped during startup; open its log for details");
            }
            try {
                api().info();
                return;
            } catch (IOException e) {
                latest = e;
            }
            Thread.sleep(300);
        }
        throw new IOException("The bundled node did not become ready before the startup deadline", latest);
    }

    public synchronized void stop() throws IOException {
        Process child = process;
        if (child == null || !child.toHandle().isAlive()) {
            Files.deleteIfExists(home.resolve("node-process.json"));
            return;
        }
        stopOwnedProcess(child.toHandle(), api());
        Files.deleteIfExists(home.resolve("node-process.json"));
    }

    private static void stopOwnedProcess(ProcessHandle child, NodeApi api) throws IOException {
        boolean interrupted = false;
        try {
            try {
                api.shutdown();
            } catch (IOException ignored) {
                child.destroy();
            }
            if (!awaitExit(child, 30)) {
                child.destroy();
                if (!awaitExit(child, 5)) {
                    child.destroyForcibly();
                    if (!awaitExit(child, 5)) {
                        throw new IOException("The managed node could not be stopped; its data directory remains locked");
                    }
                }
            }
        } catch (InterruptedException e) {
            interrupted = true;
            child.destroy();
            try {
                if (!awaitExit(child, 5)) {
                    child.destroyForcibly();
                    awaitExit(child, 5);
                }
            } catch (InterruptedException second) {
                child.destroyForcibly();
            }
            if (child.isAlive()) {
                throw new IOException("The managed node is still stopping; its data directory remains locked");
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean awaitExit(ProcessHandle child, int seconds) throws InterruptedException, IOException {
        try {
            child.onExit().get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return !child.isAlive();
        } catch (ExecutionException e) {
            throw new IOException("Cannot observe the managed node shutdown", e.getCause());
        }
    }

    private void recoverPreviousNode() throws IOException {
        Path record = home.resolve("node-process.json");
        if (!Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requirePrivateFile(record);
        Map<String, Object> data;
        long pid;
        Instant started;
        try {
            data = Json.asObject(Json.parse(Files.readString(record, StandardCharsets.UTF_8)));
            pid = Json.asLong(data.get("pid"));
            started = Instant.parse((String) data.get("started"));
            if (pid < 1 || !nodeName().equals(data.get("name"))) {
                throw new IllegalArgumentException("Invalid managed process record");
            }
        } catch (RuntimeException e) {
            throw new IOException("The previous node process record is invalid. Existing wallet data has been preserved");
        }
        Optional<ProcessHandle> previous = ProcessHandle.of(pid);
        if (!previous.isPresent() || !previous.get().isAlive()) {
            Files.delete(record);
            return;
        }
        ProcessHandle child = previous.get();
        ProcessHandle.Info identity = child.info();
        if (identity.startInstant().isPresent() && !identity.startInstant().get().equals(started)) {
            Files.delete(record);
            return;
        }
        int port;
        try {
            port = Math.toIntExact(Json.asLong(data.get("apiPort")));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Invalid node port");
            }
        } catch (RuntimeException e) {
            throw new IOException("The previous node connection record is invalid. Existing wallet data has been preserved");
        }
        boolean windowsFallback = windows() && !identity.arguments().isPresent();
        boolean verified = identity.startInstant().isPresent() && identity.command().isPresent();
        if (verified) {
            try {
                verified = Files.isSameFile(Paths.get(identity.command().get()), javaPath);
                if (identity.arguments().isPresent()) {
                    List<String> args = Arrays.asList(identity.arguments().get());
                    verified = verified && containsPair(args, "-jar", nodeJar.toString())
                            && containsPair(args, "-c", home.resolve("node.conf").toString())
                            && args.contains("--testnet") && args.contains("-Djava.io.tmpdir=" + home.resolve("tmp"));
                } else {
                    verified = verified && windowsFallback && windowsOwnsSocket(pid, port);
                }
            } catch (IOException | RuntimeException e) {
                verified = false;
            }
        }
        if (!verified) {
            throw new IOException("A previous node is still running and cannot be safely identified. Close it before reopening Zyrex");
        }
        NodeApi previousApi = new NodeApi(port, apiKey, child::isAlive, nodeName());
        if (windowsFallback) {
            try {
                previousApi.info();
                previousApi.status();
            } catch (IOException e) {
                throw new IOException("The previous node could not prove its installation identity. Close it before reopening Zyrex");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Previous node verification was interrupted. Existing wallet data has been preserved");
            }
        }
        stopOwnedProcess(child, previousApi);
        Files.delete(record);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** OpenJDK 11 does not expose process arguments on Windows; verify the socket owner instead. */
    static boolean windowsOwnsSocket(long pid, int port) throws IOException {
        if (!windows() || pid < 1 || pid > 0xffffffffL || port < 1 || port > 65535) {
            return false;
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || !Paths.get(systemRoot).isAbsolute()) {
            return false;
        }
        Path modules = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "Modules");
        Path powershell = modules.getParent().resolve("powershell.exe");
        if (!Files.isRegularFile(powershell)) {
            return false;
        }
        String query = "$ErrorActionPreference='Stop'; "
                + "$owned=@(NetTCPIP\\Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort " + port
                + " -OwningProcess " + pid + " -ErrorAction SilentlyContinue); "
                + "if($owned.Count -eq 1){[Console]::Write('OWNED')}else{exit 2}";
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", query);
        builder.environment().put("PSModulePath", modules.toString());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process probe = builder.start();
        try {
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                return false;
            }
            byte[] result = probe.getInputStream().readNBytes(64);
            return probe.exitValue() == 0 && "OWNED".equals(new String(result, StandardCharsets.US_ASCII));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Previous node socket verification was interrupted");
        } finally {
            if (probe.isAlive()) {
                probe.destroyForcibly();
            }
        }
    }

    private static boolean containsPair(List<String> arguments, String option, String value) {
        int index = arguments.indexOf(option);
        return index >= 0 && index + 1 < arguments.size() && value.equals(arguments.get(index + 1));
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            stop();
            instanceLock.release();
            lockChannel.close();
            closed = true;
        }
    }

    private String readOrCreateSettings() throws IOException {
        Path settings = home.resolve("desktop.json");
        if (Files.exists(settings, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(settings);
            try {
                Map<String, Object> data = Json.asObject(Json.parse(Files.readString(settings, StandardCharsets.UTF_8)));
                Object key = data.get("apiKey");
                if (Json.asLong(data.get("version")) != 1 || !"testnet".equals(data.get("network"))
                        || !NodeApi.TESTNET_GENESIS.equals(data.get("genesis"))
                        || !(key instanceof String) || !((String) key).matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Invalid desktop network identity");
                }
                return (String) key;
            } catch (IllegalArgumentException e) {
                throw new IOException("The desktop settings are invalid. Existing wallet data has been preserved");
            }
        }
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = hex(bytes);
        Arrays.fill(bytes, (byte) 0);
        writePrivate(settings, Json.stringify(Json.object("version", 1, "network", "testnet",
                "genesis", NodeApi.TESTNET_GENESIS, "apiKey", key)).getBytes(StandardCharsets.UTF_8));
        return key;
    }

    private String keyHash() {
        Blake2bDigest digest = new Blake2bDigest(256);
        byte[] keyBytes = apiKey.getBytes(StandardCharsets.UTF_8);
        digest.update(keyBytes, 0, keyBytes.length);
        byte[] hash = new byte[32];
        digest.doFinal(hash, 0);
        Arrays.fill(keyBytes, (byte) 0);
        return hex(hash);
    }

    private String nodeName() { return "zyrex-desktop-" + keyHash().substring(0, 16); }

    private String config() {
        return "zyrex {\n"
                + "  directory = " + Json.stringify(home.resolve("node").toString()) + "\n"
                + "  networkType = \"testnet\"\n"
                + "  node { mining = false, offlineGeneration = false, useExternalMiner = false\n"
                + "    stateType = \"utxo\", verifyTransactions = true, blocksToKeep = -1\n"
                + "    utxo.utxoBootstrap = false, nipopow.nipopowBootstrap = false }\n"
                + "  wallet { seedStrengthBits = 256, mnemonicPhraseLanguage = \"english\"\n"
                + "    keepSpentBoxes = true\n"
                + "    secretStorage.secretDir = " + Json.stringify(home.resolve("node/wallet/keystore").toString()) + " }\n"
                + "}\n"
                + "scorex {\n"
                + "  dataDir = " + Json.stringify(home.resolve("node").toString()) + "\n"
                + "  logging.level = \"INFO\"\n"
                + "  network { bindAddress = \"127.0.0.1:" + p2pPort + "\", nodeName = \"" + nodeName() + "\"\n"
                + "    knownPeers = [\"zyrexchain.com:19531\", \"zyrexchain.com:19533\"]\n"
                + "    peerDiscovery = false, allowLocal = true, upnpEnabled = false, declaredAddress = null, maxConnections = 8 }\n"
                + "  restApi { bindAddress = \"127.0.0.1:" + apiPort + "\", apiKeyHash = \"" + keyHash() + "\"\n"
                + "    corsAllowedOrigin = null, timeout = 30s, publicUrl = null }\n"
                + "}\n";
    }

    private String logging() {
        String path = home.resolve("node.log").toString().replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        return "<configuration>\n"
                + " <appender name=\"FILE\" class=\"ch.qos.logback.core.rolling.RollingFileAppender\">\n"
                + "  <file>" + path + "</file>\n"
                + "  <rollingPolicy class=\"ch.qos.logback.core.rolling.FixedWindowRollingPolicy\">\n"
                + "   <fileNamePattern>" + path + ".%i.gz</fileNamePattern><minIndex>1</minIndex><maxIndex>4</maxIndex>\n"
                + "  </rollingPolicy>\n"
                + "  <triggeringPolicy class=\"ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy\">\n"
                + "   <maxFileSize>5MB</maxFileSize></triggeringPolicy>\n"
                + "  <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss} %-5level %logger{24} - %msg%n</pattern></encoder>\n"
                + " </appender><root level=\"INFO\"><appender-ref ref=\"FILE\"/></root>\n"
                + "</configuration>\n";
    }

    private static ServerSocket freeSocket() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        return socket;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 15, 16));
            result.append(Character.forDigit(value & 15, 16));
        }
        return result.toString();
    }

    private static void privateDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                throw new IOException("Desktop data paths must be real directories");
            }
        } else {
            Files.createDirectories(directory);
        }
        protect(directory, true);
    }

    private static void createPrivateFile(Path path, byte[] bytes) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
            protect(path, false);
        }
        Files.write(path, bytes, StandardOpenOption.WRITE);
    }

    private static void writePrivate(Path path, byte[] bytes) throws IOException {
        writePrivate(path, bytes, true);
    }

    private static void writePrivate(Path path, byte[] bytes, boolean limitExistingSize) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(path, limitExistingSize);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".new");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateFile(temporary);
            Files.delete(temporary);
        }
        createPrivateFile(temporary, bytes);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requirePrivateFile(Path path) throws IOException {
        requirePrivateFile(path, true);
    }

    private static void requirePrivateFile(Path path, boolean limitSize) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
                || (limitSize && Files.size(path) > 16384)) {
            throw new IOException("Desktop settings must be regular private files");
        }
        protect(path, false);
    }

    private static void protect(Path path, boolean directory) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("The data filesystem must support private file permissions");
        }
        UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
        AclEntry.Builder entryBuilder = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) {
            entryBuilder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        }
        AclEntry entry = entryBuilder.build();
        acl.setAcl(Collections.singletonList(entry));
    }
}
