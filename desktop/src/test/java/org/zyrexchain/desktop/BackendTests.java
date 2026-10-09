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
        historyUsesNativePaginationAndKeepsSubmissionsOnFailure();
        historySeparatesTransfersChangeAndConfirmation();
        submissionHistoryIsPrivateBoundedAndPinned();
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

    private static void historyUsesNativePaginationAndKeepsSubmissionsOnFailure() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        AtomicInteger broadcasts = new AtomicInteger();
        WalletHistory history = new WalletHistory(Files.createTempDirectory("zyrex-history-transport-"));
        String txId = "a".repeat(64);
        history.record(txId, ADDRESS, ADDRESS, 1, Units.DEFAULT_FEE);
        try (TestServer server = new TestServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) { reply(exchange, 200, infoJson("test")); return; }
            check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "History uses the verified private transport");
            if (path.equals("/wallet/transactions")) {
                check(exchange.getRequestURI().getQuery() == null, "Registry history has no pagination or pending switch");
                reply(exchange, 200, "[]");
            } else if (path.equals("/transactions/unconfirmed")) {
                int page = pages.getAndIncrement();
                check(exchange.getRequestURI().getQuery().equals("offset=" + page * 100 + "&limit=100"), "Actual native mempool pagination");
                reply(exchange, 200, Json.stringify(java.util.Collections.nCopies(page == 0 ? 100 : 1, Json.object("id", txId))));
            } else if (path.equals("/wallet/transaction/send")) {
                broadcasts.incrementAndGet();
                reply(exchange, 503, "{}");
            } else throw new AssertionError("Unexpected history request");
        })) {
            NodeApi api = new NodeApi(server.port(), KEY, () -> true);
            check(api.transactions().isEmpty(), "Native confirmed registry reads remain separate");
            check(api.unconfirmedTransactions().size() == 101 && pages.get() == 2, "A default 50-row page cannot hide pending transactions");
            try {
                api.send(ADDRESS, 1, Units.DEFAULT_FEE);
                throw new AssertionError("Ambiguous broadcasts must report uncertainty");
            } catch (IOException failure) {
                check(failure.getMessage().contains("unknown") && failure.getMessage().contains("before retrying"),
                        "An ambiguous send must direct the user to history");
            }
            check(broadcasts.get() == 1, "An ambiguous broadcast is never retried automatically");
        }
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/info")) reply(exchange, 200, infoJson("test"));
            else reply(exchange, 503, "{}");
        })) {
            NodeApi api = new NodeApi(server.port(), KEY, () -> true);
            rejects(IOException.class, api::unconfirmedTransactions);
            WalletHistory.View last = new WalletHistory.View(history.rows(List.of(), List.of(), List.of(ADDRESS)), "");
            WalletHistory.View failed = history.refresh(api, List.of(ADDRESS), last);
            check(failed.rows.size() == 1 && failed.rows.get(0).id.equals(txId) && !failed.warning.isEmpty(),
                    "A failed history refresh preserves existing rows and reports that they are stale");
            WalletHistory.View reopened = history.refresh(api, List.of(ADDRESS), WalletHistory.View.empty());
            check(reopened.rows.size() == 1, "Saved submissions remain visible if history is unavailable after restart");
        }
        check(history.rows(List.of(), List.of(), List.of(ADDRESS)).get(0).id.equals(txId), "Transport failures cannot erase submitted IDs");
        passed++;
    }

    private static void historySeparatesTransfersChangeAndConfirmation() throws Exception {
        WalletHistory history = new WalletHistory(Files.createTempDirectory("zyrex-history-amounts-"));
        String incomingId = "1".repeat(64);
        String outgoingId = "2".repeat(64);
        String internalId = "3".repeat(64);
        String ownedBox = "4".repeat(64);
        String changeBox = "5".repeat(64);
        long amount = 9_007_199_254_740_993L;
        long fee = Units.DEFAULT_FEE;
        long change = 8_000_000_001L;
        Map<String, Object> incoming = historyTx(incomingId, 8, List.of(),
                List.of(historyBox(ownedBox, ADDRESS, amount + fee + change, "owned")));
        Map<String, Object> outgoing = historyTx(outgoingId, 10, List.of(Json.object("boxId", ownedBox)), List.of(
                historyBox("6".repeat(64), "external", amount, "external"),
                historyBox(changeBox, ADDRESS, change, "owned"),
                historyBox("7".repeat(64), "fee", fee, WalletHistory.feeTree())));
        Map<String, Object> pending = Json.object("id", outgoingId, "inputs", outgoing.get("inputs"), "outputs", outgoing.get("outputs"));
        List<WalletHistory.Row> rows = history.rows(List.of(incoming), List.of(pending, pending), List.of(ADDRESS));
        WalletHistory.Row sent = historyRow(rows, outgoingId);
        check(rows.size() == 2 && sent.direction.equals("Sent") && !sent.included, "Pending outgoing duplicates produce one unconfirmed row");
        check(sent.amount.equals(Units.format(amount)) && sent.fee.equals("0.001"), "Sent amount and fee preserve every nano above 2^53");
        check(sent.net.equals(Units.format(-amount - fee)), "Outgoing change is excluded from sent amount and included in net accounting");
        rows = history.rows(List.of(incoming, outgoing), List.of(pending, pending), List.of(ADDRESS));
        sent = historyRow(rows, outgoingId);
        check(rows.size() == 2 && sent.included && sent.confirmations == 1 && sent.status.contains("block 10"),
                "Confirmed registry provenance wins duplicates even when the native confirmation count is zero");
        check(sent.sendStatus.startsWith("Included in block 10"), "Send status follows actual block inclusion");
        WalletHistory.Row received = historyRow(rows, incomingId);
        check(received.direction.equals("Received") && received.amount.equals(Units.format(amount + fee + change))
                && received.net.startsWith("+") && received.fee.equals("—"), "Receipts show exact owned outputs without charging someone else's fee");
        Map<String, Object> internal = historyTx(internalId, 11, List.of(Json.object("boxId", changeBox)), List.of(
                historyBox("8".repeat(64), ADDRESS, change - fee, "owned"),
                historyBox("9".repeat(64), "fee", fee, WalletHistory.feeTree())));
        WalletHistory.Row self = historyRow(history.rows(List.of(incoming, outgoing, internal), List.of(), List.of(ADDRESS)), internalId);
        check(self.direction.equals("Self transfer") && self.amount.equals("—") && self.net.equals("-0.001"),
                "An internal transfer never double-counts its amount as wallet income or expenditure");
        WalletHistory recovered = new WalletHistory(Files.createTempDirectory("zyrex-history-recovered-"));
        check(historyRow(recovered.rows(List.of(incoming, outgoing), List.of(), List.of(ADDRESS)), outgoingId).amount.equals(Units.format(amount)),
                "Recovered native history explains outgoing transfers without a local submission record");
        history.record(outgoingId, ADDRESS, ADDRESS, 1, Units.DEFAULT_FEE);
        WalletHistory.Row contradictory = historyRow(history.rows(List.of(outgoing), List.of(), List.of(ADDRESS)), outgoingId);
        check(contradictory.included && contradictory.direction.equals("Wallet activity") && contradictory.amount.equals("—")
                && contradictory.net.equals("—"), "Saved claims cannot override contradictory native transaction outputs");
        Map<String, Object> unknown = historyTx("f".repeat(64), 12, List.of(),
                List.of(historyBox("0".repeat(64), "unknown script", 1000, "unknown")));
        WalletHistory.Row unknownActivity = historyRow(history.rows(List.of(unknown), List.of(), List.of(ADDRESS)), "f".repeat(64));
        check(unknownActivity.direction.equals("Wallet activity") && unknownActivity.net.equals("—"),
                "A native scan record with no resolved owned boxes is never mislabeled as a zero-value receipt");
        String script = org.ergoplatform.ZyrexAddressEncoder.apply((byte) 64).fromString(ADDRESS).get().script().bytesHex();
        Map<String, Object> mempoolReceipt = Json.object("id", "b".repeat(64), "inputs", List.of(),
                "outputs", List.of(historyBox("c".repeat(64), null, 1, script)));
        WalletHistory.Row receipt = historyRow(history.rows(List.of(), List.of(mempoolReceipt), List.of(ADDRESS)), "b".repeat(64));
        check(receipt.amount.equals("0.000000001") && receipt.direction.equals("Received") && !receipt.included,
                "Raw native mempool boxes are recognized by the owned address script when address fields are absent");
        passed++;
    }

    private static void submissionHistoryIsPrivateBoundedAndPinned() throws Exception {
        Path home = Files.createTempDirectory("zyrex-history-storage-");
        WalletHistory history = new WalletHistory(home);
        String id = "d".repeat(64);
        check(history.record(id, ADDRESS, ADDRESS, 1, Units.DEFAULT_FEE).isEmpty(), "Acknowledged submissions persist successfully");
        WalletHistory.Row row = new WalletHistory(home).rows(List.of(), List.of(), List.of(ADDRESS)).get(0);
        check(!row.included && row.status.contains("inclusion not found") && row.net.equals("-0.001"),
                "A restart preserves a submitted self transfer without inventing confirmation or a mempool observation");
        Path file = home.resolve("submitted-transactions.json");
        Map<String, Object> stored = Json.asObject(Json.parse(Files.readString(file)));
        check(Json.asObject(Json.asList(stored.get("submissions")).get(0)).keySet().equals(
                java.util.Set.of("id", "wallet", "recipient", "amount", "fee", "submittedAt")),
                "Metadata contains no seed, password or raw signed transaction");
        if (!windows()) check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")), "Private submission metadata");
        Path copied = Files.createTempDirectory("zyrex-history-pin-");
        Files.copy(file, copied.resolve(file.getFileName()));
        WalletHistory foreign = new WalletHistory(copied);
        check(foreign.rows(List.of(), List.of(), List.of(ADDRESS)).isEmpty() && !foreign.warning().isEmpty(),
                "Copied submission metadata cannot cross application data homes");
        String preserved = Files.readString(copied.resolve(file.getFileName()));
        foreign.record("e".repeat(64), ADDRESS, ADDRESS, 1, Units.DEFAULT_FEE);
        check(Files.readString(copied.resolve(file.getFileName())).equals(preserved), "A pin mismatch never rewrites the saved file");
        stored.put("genesis", "0".repeat(64));
        Files.writeString(file, Json.stringify(stored));
        WalletHistory wrongChain = new WalletHistory(home);
        check(wrongChain.rows(List.of(), List.of(), List.of(ADDRESS)).isEmpty(), "Saved submissions are pinned to the public testnet genesis");
        Path bounded = Files.createTempDirectory("zyrex-history-bounded-");
        WalletHistory many = new WalletHistory(bounded);
        for (int index = 0; index < 251; index++) many.record(String.format("%064x", index), ADDRESS, ADDRESS, 1, Units.DEFAULT_FEE);
        List<WalletHistory.Row> latest = new WalletHistory(bounded).rows(List.of(), List.of(), List.of(ADDRESS));
        check(latest.size() == 250 && latest.stream().noneMatch(item -> item.id.equals("0".repeat(64))), "Local submission metadata is bounded");
        passed++;
    }

    private static Map<String, Object> historyTx(String id, int height, List<Object> inputs, List<Object> outputs) {
        return Json.object("id", id, "inclusionHeight", height, "numConfirmations", 0, "inputs", inputs, "outputs", outputs);
    }

    private static Map<String, Object> historyBox(String id, String address, long value, String script) {
        return Json.object("boxId", id, "address", address, "value", value, "ergoTree", script);
    }

    private static WalletHistory.Row historyRow(List<WalletHistory.Row> rows, String id) {
        return rows.stream().filter(row -> row.id.equals(id)).findFirst().orElseThrow(() -> new AssertionError("Missing history ID"));
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
