package org.zyrexchain.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.digests.Blake2bDigest;

/** Executable backend regressions; mock nodes are confined to temporary test storage. */
public final class BackendTests {
    private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94";
    private static final String PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static int passed;

    private BackendTests() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && args[0].equals("--orphan-parent")) {
            NodeManager manager = new NodeManager(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
            manager.start();
            manager.awaitReady(Duration.ofSeconds(20));
            Runtime.getRuntime().halt(0);
        }
        exactUnitsAndJson();
        bootstrapPathsPreserveUnicodeAndRejectMalformedInput();
        recoveryPhraseValidation();
        recoveryPhraseGenerationMatchesBip39();
        addressChecksums();
        wrongIdentityNeverReceivesCredentials();
        redirectsNeverForwardCredentials();
        restoreUsesCurrentDerivationAndRescans();
        restoreHandlesNativeAutomaticUnlock();
        unlockHandlesNativeAutomaticUnlockRace();
        wrongUnlockPasswordRemainsRejected();
        sendPreservesExactAmounts();
        errorsDoNotExposeSecretsAndResponsesAreBounded();
        managedLifecycleAndSingleInstance();
        logRestartPreservesFileSafetyAndSettingsLimits();
        orphanRecoveryStopsOnlyVerifiedProcess();
        unrelatedLiveProcessesArePreserved();
        System.out.println("Desktop backend: " + passed + " tests passed");
    }

    private static void exactUnitsAndJson() throws Exception {
        check(Units.parse("0.000000001") == 1, "One nano unit must survive display parsing");
        check(Units.parse("9223372036.854775807") == Long.MAX_VALUE, "Maximum signed integer must remain exact");
        check(Units.format(Long.MAX_VALUE).equals("9223372036.854775807"), "Exact amount formatting");
        for (String invalid : Arrays.asList("0", "-1", "1e3", "NaN", "1.0000000001", "9223372036.854775808", "1.", "+1")) {
            rejects(IllegalArgumentException.class, () -> Units.parse(invalid));
        }
        Map<String, Object> parsed = Json.asObject(Json.parse("{\"value\":9223372036854775807,\"text\":\"line\\n\\u263a\"}"));
        check(Json.asLong(parsed.get("value")) == Long.MAX_VALUE, "JSON must preserve signed 64-bit integers");
        check(Json.stringify(parsed).contains("9223372036854775807"), "JSON serializer must preserve integers");
        check(Json.asLong(new BigInteger("9007199254740993")) == 9007199254740993L, "Values above floating-point precision");
        for (String invalid : Arrays.asList("{\"x\":1,\"x\":2}", "[01]", "[1,]", "\"\\ud800\"", "1 2")) {
            rejects(IllegalArgumentException.class, () -> Json.parse(invalid));
        }
        passed++;
    }

    private static void recoveryPhraseValidation() throws Exception {
        check(NodeApi.validateMnemonic(PHRASE.toUpperCase(java.util.Locale.ROOT).replace(" ", "\n")).equals(PHRASE),
                "Recovery phrase whitespace normalization");
        rejects(IllegalArgumentException.class, () -> NodeApi.validateMnemonic(PHRASE.replace("about", "abandon")));
        rejects(IllegalArgumentException.class, () -> NodeApi.validateMnemonic(PHRASE.replace("about", "unknownword")));
        rejects(IllegalArgumentException.class, () -> NodeApi.validateMnemonic("abandon about"));
        passed++;
    }

    private static void bootstrapPathsPreserveUnicodeAndRejectMalformedInput() throws Exception {
        Path unicode = Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().resolve("wallet folder \u03a9");
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(unicode.toString().getBytes(StandardCharsets.UTF_8));
        check(encoded.chars().allMatch(character -> character < 128), "Bootstrap command arguments must be ASCII");
        check(NodeBootstrap.decodePath(encoded).equals(unicode.normalize()), "UTF-8 bootstrap paths preserve Unicode and spaces");
        for (String invalid : Arrays.asList("", "!", "====", "YQ", "_w")) {
            rejects(IllegalArgumentException.class, () -> NodeBootstrap.decodePath(invalid));
        }
        rejects(IllegalArgumentException.class, () -> NodeBootstrap.decodePath(encoded + "="));
        passed++;
    }

    private static void addressChecksums() throws Exception {
        NodeApi.validateAddress(ADDRESS);
        rejects(IllegalArgumentException.class, () -> NodeApi.validateAddress(ADDRESS.substring(3)));
        rejects(IllegalArgumentException.class, () -> NodeApi.validateAddress(ADDRESS.substring(0, ADDRESS.length() - 1) + "5"));
        rejects(IllegalArgumentException.class, () -> NodeApi.validateAddress("ZRX1111"));
        passed++;
    }

    private static void recoveryPhraseGenerationMatchesBip39() throws Exception {
        String expected = String.join(" ", java.util.Collections.nCopies(23, "abandon")) + " art";
        check(NodeApi.fromEntropy(new byte[32]).equals(expected), "BIP39 zero-entropy 256-bit vector");
        rejects(IllegalArgumentException.class, () -> NodeApi.fromEntropy(new byte[16]));
        String first = NodeApi.createRecoveryPhrase();
        String second = NodeApi.createRecoveryPhrase();
        check(first.split(" ").length == 24 && second.split(" ").length == 24, "Generated 256-bit phrases contain 24 words");
        check(NodeApi.validateMnemonic(first).equals(first) && NodeApi.validateMnemonic(second).equals(second),
                "Generated phrase word indexes and checksums must validate independently");
        check(!first.equals(second), "Independent wallets must receive independent recovery phrases");
        passed++;
    }

    private static void wrongIdentityNeverReceivesCredentials() throws Exception {
        AtomicInteger walletRequests = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            check(exchange.getRequestHeaders().getFirst("api_key") == null, "Identity checks must be anonymous");
            if (exchange.getRequestURI().getPath().equals("/info")) {
                reply(exchange, 200, "{\"network\":\"foreign\",\"genesisBlockId\":\"" + NodeApi.TESTNET_GENESIS + "\"}");
            } else {
                walletRequests.incrementAndGet();
                reply(exchange, 200, "{}");
            }
        })) {
            NodeApi api = new NodeApi(server.port(), KEY, () -> true);
            rejects(IOException.class, api::status);
            check(walletRequests.get() == 0, "Foreign nodes must never receive wallet requests");
            NodeApi stopped = new NodeApi(server.port(), KEY, () -> false);
            rejects(IOException.class, stopped::info);
        }
        try (TestServer server = new TestServer(exchange -> reply(exchange, 200, infoJson("other-installation")))) {
            NodeApi api = new NodeApi(server.port(), KEY, () -> true, "this-installation");
            rejects(IOException.class, api::status);
        }
        passed++;
    }

    private static void redirectsNeverForwardCredentials() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        try (TestServer target = new TestServer(exchange -> {
            redirected.incrementAndGet();
            reply(exchange, 200, "{}");
        }); TestServer origin = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/info")) {
                reply(exchange, 200, infoJson("test"));
            } else {
                check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "Own verified origin receives its key");
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.port() + "/wallet/status");
                reply(exchange, 307, "{}");
            }
        })) {
            rejects(IOException.class, new NodeApi(origin.port(), KEY, () -> true)::status);
            check(redirected.get() == 0, "HTTP redirects must never forward credentials");
        }
        passed++;
    }

    private static void restoreUsesCurrentDerivationAndRescans() throws Exception {
        List<String> actions = new ArrayList<>();
        try (TestServer server = new TestServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) {
                reply(exchange, 200, infoJson("test"));
                return;
            }
            check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "Authenticated restore");
            actions.add(path);
            if (path.equals("/wallet/status")) {
                reply(exchange, 200, "{\"isInitialized\":true,\"isUnlocked\":false}");
                return;
            }
            Map<String, Object> payload = Json.asObject(Json.parse(readBody(exchange)));
            if (path.equals("/wallet/restore")) {
                check(Boolean.FALSE.equals(payload.get("usePre1627KeyDerivation")), "Use current EIP3 derivation");
                check(PHRASE.equals(payload.get("mnemonic")), "Preserve normalized recovery phrase");
                check("test-password".equals(payload.get("pass")), "Restore password");
            } else if (path.equals("/wallet/unlock")) {
                check("test-password".equals(payload.get("pass")), "Unlock restored wallet");
            } else if (path.equals("/wallet/rescan")) {
                check(Json.asLong(payload.get("fromHeight")) == 1, "Restoration must scan all retained history");
            }
            reply(exchange, 200, "\"OK\"");
        })) {
            NodeApi api = new NodeApi(server.port(), KEY, () -> true);
            rejects(IllegalArgumentException.class, () -> api.restore(PHRASE.replace("about", "abandon"), "test-password"));
            check(actions.isEmpty(), "Invalid phrases must not initialize native wallet data");
            api.restore(PHRASE, "test-password");
            check(actions.equals(Arrays.asList("/wallet/restore", "/wallet/status", "/wallet/unlock", "/wallet/rescan")),
                    "Restore operation order");
        }
        passed++;
    }

    private static void restoreHandlesNativeAutomaticUnlock() throws Exception {
        List<String> actions = new ArrayList<>();
        try (TestServer server = new TestServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) {
                reply(exchange, 200, infoJson("test"));
                return;
            }
            actions.add(path);
            if (path.equals("/wallet/status")) {
                reply(exchange, 200, "{\"isInitialized\":true,\"isUnlocked\":true}");
            } else if (path.equals("/wallet/restore")) {
                reply(exchange, 200, "\"OK\"");
            } else if (path.equals("/wallet/rescan")) {
                check(Json.asLong(Json.asObject(Json.parse(readBody(exchange))).get("fromHeight")) == 1,
                        "Automatically unlocked restored wallets still scan all retained history");
                reply(exchange, 200, "\"OK\"");
            } else {
                throw new AssertionError("An already unlocked wallet must not receive another unlock request");
            }
        })) {
            new NodeApi(server.port(), KEY, () -> true).restore(PHRASE, "test-password");
            check(actions.equals(Arrays.asList("/wallet/restore", "/wallet/status", "/wallet/rescan")),
                    "Native automatic unlock is reconciled before rescan");
        }
        passed++;
    }

    private static void unlockHandlesNativeAutomaticUnlockRace() throws Exception {
        AtomicInteger statuses = new AtomicInteger();
        AtomicInteger unlocks = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) {
                reply(exchange, 200, infoJson("test"));
            } else if (path.equals("/wallet/status")) {
                boolean unlocked = statuses.incrementAndGet() >= 2;
                reply(exchange, 200, Json.stringify(Json.object("isInitialized", true, "isUnlocked", unlocked)));
            } else if (path.equals("/wallet/unlock")) {
                unlocks.incrementAndGet();
                reply(exchange, 400, "{\"detail\":\"Wallet already unlocked\"}");
            } else {
                throw new AssertionError("Unexpected unlock race request");
            }
        })) {
            new NodeApi(server.port(), KEY, () -> true).unlock("test-password");
            check(unlocks.get() == 1 && statuses.get() == 2, "Reconcile an automatic unlock between status and unlock RPCs");
        }
        passed++;
    }

    private static void wrongUnlockPasswordRemainsRejected() throws Exception {
        AtomicInteger statuses = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) {
                reply(exchange, 200, infoJson("test"));
            } else if (path.equals("/wallet/status")) {
                statuses.incrementAndGet();
                reply(exchange, 200, "{\"isInitialized\":true,\"isUnlocked\":false}");
            } else {
                reply(exchange, 400, "{\"detail\":\"Invalid password\"}");
            }
        })) {
            rejects(NodeApi.ApiException.class, () -> new NodeApi(server.port(), KEY, () -> true).unlock("wrong-password"));
            check(statuses.get() == 2, "A failed unlock must be rejected when fresh native status remains locked");
        }
        passed++;
    }

    private static void sendPreservesExactAmounts() throws Exception {
        String txId = "ab".repeat(32);
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/info")) {
                reply(exchange, 200, infoJson("test"));
                return;
            }
            check(exchange.getRequestMethod().equals("POST"), "Transactions must use POST");
            check(exchange.getRequestURI().getPath().equals("/wallet/transaction/send"), "Native transaction signing endpoint");
            check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "Send credential");
            Map<String, Object> payload = Json.asObject(Json.parse(readBody(exchange)));
            Map<String, Object> payment = Json.asObject(Json.asList(payload.get("requests")).get(0));
            check(ADDRESS.equals(payment.get("address")), "Exact recipient");
            check(Json.asLong(payment.get("value")) == 9007199254740993L, "No floating-point transfer amount");
            check(Json.asLong(payload.get("fee")) == Units.DEFAULT_FEE, "Explicit exact fee");
            reply(exchange, 200, "\"" + txId + "\"");
        })) {
            String submitted = new NodeApi(server.port(), KEY, () -> true).send(ADDRESS, 9007199254740993L, Units.DEFAULT_FEE);
            check(txId.equals(submitted), "Return native transaction ID");
        }
        passed++;
    }

    private static void errorsDoNotExposeSecretsAndResponsesAreBounded() throws Exception {
        String secret = "test-password-should-never-appear";
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/info")) {
                reply(exchange, 200, infoJson("test"));
            } else if (exchange.getRequestURI().getPath().equals("/wallet/status")) {
                reply(exchange, 200, "{\"isInitialized\":true,\"isUnlocked\":false}");
            } else {
                reply(exchange, 400, Json.stringify(Json.object("detail", "Invalid request pass=" + secret)));
            }
        })) {
            try {
                new NodeApi(server.port(), KEY, () -> true).unlock(secret);
                throw new AssertionError("Failed unlock must report an error");
            } catch (NodeApi.ApiException error) {
                check(!error.getMessage().contains(secret), "Native errors must never echo passwords");
                check(error.statusCode() == 400, "Error retains status classification");
            }
        }
        try (TestServer server = new TestServer(exchange -> reply(exchange, 200, " ".repeat(4 * 1024 * 1024 + 1)))) {
            rejects(IOException.class, new NodeApi(server.port(), KEY, () -> true)::info);
        }
        passed++;
    }

    private static void managedLifecycleAndSingleInstance() throws Exception {
        Path temporary = Files.createTempDirectory("zyrex-desktop-backend-");
        Path jar = fakeNodeJar(temporary);
        Path java = Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        Path data = temporary.resolve("private-data");
        String settings;
        try (NodeManager manager = new NodeManager(data, java, jar)) {
            rejects(IOException.class, () -> new NodeManager(data, java, jar));
            manager.start();
            manager.awaitReady(Duration.ofSeconds(20));
            check(!Boolean.TRUE.equals(manager.api().status().get("isInitialized")), "Managed API readiness");
            String config = Files.readString(data.resolve("node.conf"));
            check(config.contains("zyrexchain.com:19531") && config.contains("zyrexchain.com:19533"), "Both public bootstrap peers");
            check(config.contains("mining = false") && config.contains("stateType = \"utxo\""), "Non-mining verified full node");
            check(config.contains("127.0.0.1:" + manager.apiPort()), "Private native API");
            if (windows()) {
                long pid = Json.asLong(Json.asObject(Json.parse(Files.readString(data.resolve("node-process.json")))).get("pid"));
                check(NodeManager.windowsOwnsSocket(pid, manager.apiPort()), "Windows TCP ownership proves the managed API process");
                check(!NodeManager.windowsOwnsSocket(ProcessHandle.current().pid(), manager.apiPort()),
                        "An unrelated Java PID cannot claim the managed API socket");
            }
            settings = Files.readString(data.resolve("desktop.json"));
            String key = (String) Json.asObject(Json.parse(settings)).get("apiKey");
            check(!config.contains(key), "Node config contains only a key hash");
            Files.writeString(data.resolve("node/preserved-data"), "wallet-history-marker");
            if (!windows()) {
                check(Files.getPosixFilePermissions(data).equals(PosixFilePermissions.fromString("rwx------")), "Private data directory");
                check(Files.getPosixFilePermissions(data.resolve("desktop.json")).equals(PosixFilePermissions.fromString("rw-------")),
                        "Private credential file");
            }
            manager.stop();
            check(!manager.isRunning(), "Stop only the owned child");
            Files.writeString(data.resolve("launcher.log"), "Previous startup diagnostic\n".repeat(2048));
            check(Files.size(data.resolve("launcher.log")) > 16384, "The restart regression must contain a large existing log");
            manager.start();
            manager.awaitReady(Duration.ofSeconds(20));
            check(Files.size(data.resolve("launcher.log")) == 0, "Restart truncates a large owned log before starting the child");
            check(Files.readString(data.resolve("node/preserved-data")).equals("wallet-history-marker"), "Restart preserves node data");
        }
        try (NodeManager reopened = new NodeManager(data, java, jar)) {
            check(settings.equals(Files.readString(data.resolve("desktop.json"))), "Credentials survive application restarts");
        }
        passed++;
    }

    private static void logRestartPreservesFileSafetyAndSettingsLimits() throws Exception {
        Path temporary = Files.createTempDirectory("zyrex-desktop-file-safety-");
        Path jar = fakeNodeJar(temporary);
        Path java = Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        Path oversized = temporary.resolve("oversized-settings");
        try (NodeManager ignored = new NodeManager(oversized, java, jar)) {
            Files.writeString(oversized.resolve("desktop.json"), " ".repeat(16385));
        }
        rejects(IOException.class, () -> new NodeManager(oversized, java, jar));
        check(Files.size(oversized.resolve("desktop.json")) == 16385, "Reject oversized settings without rewriting them");
        Path data = temporary.resolve("symlink-log");
        try (NodeManager manager = new NodeManager(data, java, jar)) {
            Path target = temporary.resolve("unrelated-file");
            Files.writeString(target, "preserve unrelated file");
            boolean canCreateSymlink = true;
            try {
                Files.createSymbolicLink(data.resolve("launcher.log"), target);
            } catch (UnsupportedOperationException | IOException | SecurityException failure) {
                if (!windows()) {
                    throw failure;
                }
                canCreateSymlink = false;
            }
            if (canCreateSymlink) {
                rejects(IOException.class, manager::start);
                check(!manager.isRunning(), "A symlink log must be rejected before starting a child");
                check(Files.readString(target).equals("preserve unrelated file"), "A symlink log must never truncate its target");
            }
        }
        passed++;
    }

    private static void orphanRecoveryStopsOnlyVerifiedProcess() throws Exception {
        Path temporary = Files.createTempDirectory("zyrex-desktop-orphan-");
        Path jar = fakeNodeJar(temporary);
        Path java = Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        Path data = temporary.resolve("private-data");
        Process crashedParent = new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                BackendTests.class.getName(), "--orphan-parent", data.toString(), java.toString(), jar.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("parent.log").toFile()).start();
        check(crashedParent.waitFor() == 0, "Crash simulation must leave only its own test child");
        long previous = Json.asLong(Json.asObject(Json.parse(Files.readString(data.resolve("node-process.json")))).get("pid"));
        check(ProcessHandle.of(previous).map(ProcessHandle::isAlive).orElse(false), "Test orphan exists before recovery");
        try (NodeManager manager = new NodeManager(data, java, jar)) {
            check(!ProcessHandle.of(previous).map(ProcessHandle::isAlive).orElse(false), "Verified orphan is stopped before replacement");
            manager.start();
            manager.awaitReady(Duration.ofSeconds(20));
        } finally {
            ProcessHandle.of(previous).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
        passed++;
    }

    private static void unrelatedLiveProcessesArePreserved() throws Exception {
        Path temporary = Files.createTempDirectory("zyrex-desktop-process-safety-");
        Path jar = fakeNodeJar(temporary);
        Path java = Paths.get(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java");
        Path first = temporary.resolve("first");
        Path second = temporary.resolve("second");
        try (NodeManager other = new NodeManager(first, java, jar)) {
            other.start();
            other.awaitReady(Duration.ofSeconds(20));
            Map<String, Object> otherRecord = Json.asObject(Json.parse(Files.readString(first.resolve("node-process.json"))));
            Map<String, Object> forged;
            try (NodeManager own = new NodeManager(second, java, jar)) {
                own.start();
                own.awaitReady(Duration.ofSeconds(20));
                forged = Json.asObject(Json.parse(Files.readString(second.resolve("node-process.json"))));
            }
            forged.put("pid", otherRecord.get("pid"));
            forged.put("started", otherRecord.get("started"));
            Files.writeString(second.resolve("node-process.json"), Json.stringify(forged));
            rejects(IOException.class, () -> new NodeManager(second, java, jar));
            check(other.isRunning(), "A different data directory's process must not be terminated");
            check(other.api().info().get("genesisBlockId").equals(NodeApi.TESTNET_GENESIS), "Other process remains available");
        }
        passed++;
    }

    private static Path fakeNodeJar(Path directory) throws IOException {
        Path jar = directory.resolve("test-node.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeNode.class.getName());
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
                Blake2bDigest.class.getProtectionDomain().getCodeSource().getLocation().toString());
        String resource = FakeNode.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar), manifest);
                InputStream bytes = BackendTests.class.getClassLoader().getResourceAsStream(resource)) {
            check(bytes != null, "Test node class must be available");
            archive.putNextEntry(new JarEntry(resource));
            bytes.transferTo(archive);
            archive.closeEntry();
        }
        return jar;
    }

    public static final class FakeNode {
        private FakeNode() { }
        public static void main(String[] args) throws Exception {
            String config = Files.readString(Paths.get(args[2]));
            Matcher port = Pattern.compile("restApi \\{ bindAddress = \\\"127\\.0\\.0\\.1:([0-9]+)\\\"").matcher(config);
            Matcher name = Pattern.compile("nodeName = \\\"([^\\\"]+)\\\"").matcher(config);
            Matcher apiHash = Pattern.compile("apiKeyHash = \\\"([0-9a-f]{64})\\\"").matcher(config);
            if (!port.find() || !name.find() || !apiHash.find()) {
                throw new IOException("Invalid test node configuration");
            }
            String expectedHash = apiHash.group(1);
            String identity = "{\"network\":\"testnet\",\"genesisBlockId\":\"" + NodeApi.TESTNET_GENESIS + "\",\"name\":\""
                    + name.group(1) + "\"}";
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(port.group(1))), 0);
            server.createContext("/", exchange -> {
                if (!exchange.getRequestURI().getPath().equals("/info")) {
                    String key = exchange.getRequestHeaders().getFirst("api_key");
                    if (key == null || !hash(key).equals(expectedHash)) {
                        exchange.sendResponseHeaders(403, -1);
                        exchange.close();
                        return;
                    }
                }
                String body = exchange.getRequestURI().getPath().equals("/info") ? identity : "{\"isInitialized\":false}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                if (exchange.getRequestURI().getPath().equals("/node/shutdown")) {
                    new Thread(() -> {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                        System.exit(0);
                    }).start();
                }
            });
            server.start();
            new CountDownLatch(1).await();
        }

        private static String hash(String key) {
            byte[] bytes = key.getBytes(StandardCharsets.UTF_8);
            Blake2bDigest digest = new Blake2bDigest(256);
            digest.update(bytes, 0, bytes.length);
            byte[] hash = new byte[32];
            digest.doFinal(hash, 0);
            StringBuilder result = new StringBuilder();
            for (byte value : hash) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String infoJson(String name) {
        return Json.stringify(Json.object("network", "testnet", "genesisBlockId", NodeApi.TESTNET_GENESIS, "name", name));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange exchange, int code, String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // A bounded client may disconnect intentionally before the response completes.
        } finally {
            exchange.close();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void rejects(Class<? extends Throwable> expected, Throwing action) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw error;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private interface Throwing { void run() throws Exception; }
    private interface ExchangeHandler { void handle(HttpExchange exchange) throws Exception; }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private volatile Throwable failure;

        private TestServer(ExchangeHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    handler.handle(exchange);
                } catch (Throwable error) {
                    failure = error;
                    reply(exchange, 500, "{}");
                }
            });
            server.start();
        }

        private int port() { return server.getAddress().getPort(); }

        @Override public void close() {
            server.stop(0);
            if (failure != null) {
                throw new AssertionError("Test server assertion failed", failure);
            }
        }
    }
}
