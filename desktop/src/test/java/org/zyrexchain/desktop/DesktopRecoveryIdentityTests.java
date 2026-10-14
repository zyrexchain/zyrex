package org.zyrexchain.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.ergoplatform.ZyrexAddressEncoder;

/** Observed-history recovery binds the saved native payload before deleting its private record. */
public final class DesktopRecoveryIdentityTests {
    private static final String KEY = "0123456789abcdef".repeat(4);
    private static final String ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94";
    private static final String TREE = ZyrexAddressEncoder.apply((byte) 64).fromString(ADDRESS).get().script().bytesHex();
    private static final String P = "f".repeat(64);
    private static final String Q = "e".repeat(64);
    private static final long AMOUNT = 2_000_000_001L;
    private static int passed;

    private DesktopRecoveryIdentityTests() { }

    public static void main(String[] args) throws Exception {
        nativeQuoteRejectsAnUnrelatedClaimedId();
        nativeRecoveryIgnoresNewFeeRecommendationsAndSpentInputs();
        nativeQuoteFailuresPreserveRecovery();
        legacyRecoveryAcceptsOnlyCanonicalPayloadsDespiteEnrichment();
        legacyRecoveryRejectsMissingCanonicalFields();
        legacyRecoveryRejectsChangedCoreBytesAndArrayOrder();
        unrelatedObservedLegacyTransactionCannotClearRecovery();
        identityAttestationCannotClearAChangedRecoveryRecord();
        System.out.println("Desktop recovery identity: " + passed + " scenarios passed");
    }

    private static void nativeQuoteRejectsAnUnrelatedClaimedId() throws Exception {
        Map<String, Object> saved = transaction(P);
        saved.put("id", Q);
        Map<String, Object> unrelated = transaction(Q);
        input(unrelated, 0).put("boxId", "b".repeat(64));
        AtomicInteger forbidden = new AtomicInteger();
        try (Server server = observedServer(unrelated, false, 200, quote(P), forbidden)) {
            rejects(() -> server.api().observedTransaction(prepared(saved)));
            check(forbidden.get() == 0, "Native ID recovery never generates, checks spent inputs or broadcasts");
            recovery(server, prepared(saved), false);
        }
        passed++;
    }

    private static void nativeRecoveryIgnoresNewFeeRecommendationsAndSpentInputs() throws Exception {
        AtomicInteger forbidden = new AtomicInteger();
        Map<String, Object> quote = quote(P);
        quote.put("feeNano", Long.toString(Long.MAX_VALUE));
        quote.put("minimumFeeNano", Long.toString(Long.MAX_VALUE));
        try (Server server = observedServer(transaction(P), false, 200, quote, forbidden)) {
            check(server.api().observedTransaction(prepared(transaction(P))), "Confirmed native identity is already complete");
            recovery(server, prepared(transaction(P)), true);
            check(forbidden.get() == 0, "Confirmed recovery does not require unspent inputs or reprice the saved fee");
        }
        try (Server server = observedServer(transaction(P), true, 200, quote, forbidden)) {
            check(server.api().observedTransaction(prepared(transaction(P))), "Mempool recovery also binds native identity");
        }
        passed++;
    }

    private static void nativeQuoteFailuresPreserveRecovery() throws Exception {
        for (int status : new int[] {400, 401, 500}) {
            try (Server server = observedServer(transaction(P), false, status, Map.of(), new AtomicInteger())) {
                recovery(server, prepared(transaction(P)), false);
            }
        }
        for (String field : new String[] {"txId", "paidFeeNano", "feeScriptHex", "quoteVersion", "txSize", "maxTransactionSize"}) {
            Map<String, Object> invalid = quote(P);
            invalid.remove(field);
            try (Server server = observedServer(transaction(P), false, 200, invalid, new AtomicInteger())) {
                recovery(server, prepared(transaction(P)), false);
            }
        }
        passed++;
    }

    private static void legacyRecoveryAcceptsOnlyCanonicalPayloadsDespiteEnrichment() throws Exception {
        for (int status : new int[] {404, 405}) {
            for (boolean mempool : new boolean[] {false, true}) {
                Map<String, Object> signed = transaction(P);
                Map<String, Object> enriched = copy(signed);
                enriched.put("inclusionHeight", 100);
                enriched.put("size", 271);
                output(enriched, 0).put("address", ADDRESS);
                output(enriched, 0).put("boxId", "c".repeat(64));
                output(enriched, 0).put("transactionId", P);
                output(enriched, 0).put("index", 0);
                output(enriched, 0).put("value", Long.toString(AMOUNT));
                input(enriched, 0).put("value", 9_000_000_000L);
                input(enriched, 0).put("ergoTree", TREE);
                AtomicInteger forbidden = new AtomicInteger();
                try (Server server = observedServer(enriched, mempool, status, Map.of(), forbidden)) {
                    check(server.api().observedTransaction(prepared(signed)), "Only unrelated enrichment is ignored on older nodes");
                    recovery(server, prepared(signed), true);
                    check(forbidden.get() == 0, "Legacy observed recovery never broadcasts or asks for a new signature");
                }
            }
        }
        passed++;
    }

    private static void legacyRecoveryRejectsMissingCanonicalFields() throws Exception {
        for (String field : new String[] {"inputs", "dataInputs", "outputs", "spendingProof", "proofBytes", "extension",
                "value", "ergoTree", "assets", "creationHeight", "additionalRegisters"}) {
            Map<String, Object> incomplete = transaction(P);
            if (field.equals("spendingProof")) input(incomplete, 0).remove(field);
            else if (field.equals("proofBytes") || field.equals("extension")) proof(incomplete).remove(field);
            else if (List.of("value", "ergoTree", "assets", "creationHeight", "additionalRegisters").contains(field)) {
                output(incomplete, 0).remove(field);
            } else incomplete.remove(field);
            try (Server server = observedServer(incomplete, false, 404, Map.of(), new AtomicInteger())) {
                recovery(server, prepared(transaction(P)), false);
            }
        }
        passed++;
    }

    private static void legacyRecoveryRejectsChangedCoreBytesAndArrayOrder() throws Exception {
        Map<String, Object> signed = transaction(P);
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> changed = copy(signed); input(changed, 0).put("boxId", "b".repeat(64)); changes.add(changed);
        changed = copy(signed); proof(changed).put("proofBytes", "ab"); changes.add(changed);
        changed = copy(signed); proof(changed).put("extension", Map.of("0", "0402")); changes.add(changed);
        changed = copy(signed); Json.asObject(Json.asList(changed.get("dataInputs")).get(0)).put("boxId", "c".repeat(64));
        changes.add(changed);
        changed = copy(signed); output(changed, 0).put("value", AMOUNT + 1); changes.add(changed);
        changed = copy(signed); output(changed, 0).put("ergoTree", "10010101d17300"); changes.add(changed);
        changed = copy(signed); output(changed, 0).put("assets", List.of(Json.object("tokenId", "b".repeat(64), "amount", 1)));
        changes.add(changed);
        changed = copy(signed); output(changed, 0).put("creationHeight", 101); changes.add(changed);
        changed = copy(signed); output(changed, 0).put("additionalRegisters", Map.of("R4", "0401")); changes.add(changed);
        changed = copy(signed); Collections.reverse(Json.asList(changed.get("inputs"))); changes.add(changed);
        changed = copy(signed); Collections.reverse(Json.asList(changed.get("dataInputs"))); changes.add(changed);
        changed = copy(signed); Collections.reverse(Json.asList(changed.get("outputs"))); changes.add(changed);
        for (Map<String, Object> observed : changes) {
            try (Server server = observedServer(observed, true, 405, Map.of(), new AtomicInteger())) {
                recovery(server, prepared(signed), false);
            }
        }
        // Assets are ordered protocol bytes too, even when each token is independently unchanged.
        Map<String, Object> assetSource = copy(signed);
        output(assetSource, 0).put("assets", List.of(Json.object("tokenId", "b".repeat(64), "amount", 1),
            Json.object("tokenId", "c".repeat(64), "amount", 2)));
        Map<String, Object> assetOrder = copy(assetSource);
        Collections.reverse(Json.asList(output(assetOrder, 0).get("assets")));
        rejects(() -> TransactionIdentity.requireSame(assetSource, assetOrder));
        passed++;
    }

    private static void unrelatedObservedLegacyTransactionCannotClearRecovery() throws Exception {
        Map<String, Object> saved = transaction(P);
        saved.put("id", Q);
        Map<String, Object> observed = transaction(Q);
        output(observed, 0).put("value", AMOUNT + 1);
        try (Server server = observedServer(observed, false, 404, Map.of(), new AtomicInteger())) {
            recovery(server, prepared(saved), false);
        }
        passed++;
    }

    private static void identityAttestationCannotClearAChangedRecoveryRecord() throws Exception {
        Path home = Files.createTempDirectory("zyrex-recovery-change-");
        PendingTransferStore store = new PendingTransferStore(home);
        PreparedTransfer saved = prepared(transaction(P));
        store.save(saved, ADDRESS);
        Path file = home.resolve("prepared-transfer.json");
        Map<String, Object> record = Json.asObject(Json.parse(Files.readString(file)));
        input(Json.asObject(record.get("transaction")), 0).put("boxId", "c".repeat(64));
        Files.writeString(file, Json.stringify(record));
        rejects(() -> store.clear(saved, List.of(ADDRESS)));
        check(store.exists(), "Identity attestation applies only to the exact saved signed payload");
        Files.delete(file);
        Files.delete(home);
        passed++;
    }

    private static void recovery(Server server, PreparedTransfer saved, boolean expectedSuccess) throws Exception {
        Path home = Files.createTempDirectory("zyrex-recovery-identity-");
        PendingTransferStore store = new PendingTransferStore(home);
        store.save(saved, ADDRESS);
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<String> success = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SendPanel panel = new SendPanel((address, amount, fee, accepted) -> { });
            SendController.Actions actions = new SendController.Actions() {
                @Override public <T> void run(String description, java.util.concurrent.Callable<T> work,
                        java.util.function.Consumer<T> accepted) {
                    try { accepted.accept(work.call()); }
                    catch (Exception problem) { failure.set(problem); }
                }
            };
            new SendController(panel, panel, new WalletHistory(home), store, server::api,
                () -> List.of(ADDRESS), () -> true, () -> Long.MAX_VALUE, actions).retry(success::set);
        });
        check(expectedSuccess == (failure.get() == null && saved.id.equals(success.get())), "Recovery outcome matches native identity");
        check(store.exists() != expectedSuccess, "Only verified observed recovery deletes the signed record");
        if (store.exists()) Files.delete(home.resolve("prepared-transfer.json"));
        Files.delete(home);
    }

    private static Map<String, Object> transaction(String id) {
        return Json.object("id", id, "inputs", List.of(
            Json.object("boxId", "a".repeat(64), "spendingProof", Json.object("proofBytes", "0102", "extension", Map.of("0", "0401"))),
            Json.object("boxId", "b".repeat(64), "spendingProof", Json.object("proofBytes", "0304", "extension", Map.of()))),
            "dataInputs", List.of(Json.object("boxId", "d".repeat(64)), Json.object("boxId", "c".repeat(64))),
            "outputs", List.of(
                Json.object("value", AMOUNT, "ergoTree", TREE, "assets", List.of(), "creationHeight", 100,
                    "additionalRegisters", Map.of()),
                Json.object("value", Units.DEFAULT_FEE, "ergoTree", WalletHistory.feeTree(), "assets", List.of(),
                    "creationHeight", 100, "additionalRegisters", Map.of())));
    }

    private static PreparedTransfer prepared(Map<String, Object> transaction) throws IOException {
        return new PreparedTransfer(ADDRESS, AMOUNT, Units.DEFAULT_FEE, transaction, "Saved fee", 0, List.of(ADDRESS));
    }

    private static Map<String, Object> quote(String id) {
        return Json.object("quoteVersion", 1, "txId", id, "paidFeeNano", Long.toString(Units.DEFAULT_FEE),
            "feeScriptHex", WalletHistory.feeTree(), "txSize", 271, "maxTransactionSize", 98304);
    }

    private static Server observedServer(Map<String, Object> observed, boolean mempool, int quoteStatus,
            Map<String, Object> quote, AtomicInteger forbidden) throws IOException {
        return new Server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/info")) {
                reply(exchange, 200, Json.object("network", "testnet", "genesisBlockId", NodeApi.TESTNET_GENESIS));
            } else {
                check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "Recovery authenticates private native reads");
                if (path.equals("/wallet/transactionById")) reply(exchange, mempool ? 404 : 200, observed);
                else if (path.startsWith("/transactions/unconfirmed/byTransactionId/")) reply(exchange, 200, observed);
                else if (path.equals("/transactions/feeQuote")) {
                    check(exchange.getRequestMethod().equals("POST"), "Native identity is derived from the signed POST payload");
                    Map<String, Object> submitted = Json.asObject(Json.parse(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    TransactionIdentity.requireSame(submitted, transaction(P));
                    reply(exchange, quoteStatus, quote);
                } else {
                    forbidden.incrementAndGet();
                    reply(exchange, 500, Map.of());
                }
            }
        });
    }

    private static Map<String, Object> copy(Map<String, Object> source) { return Json.asObject(Json.parse(Json.stringify(source))); }
    private static Map<String, Object> input(Map<String, Object> transaction, int index) {
        return Json.asObject(Json.asList(transaction.get("inputs")).get(index));
    }
    private static Map<String, Object> output(Map<String, Object> transaction, int index) {
        return Json.asObject(Json.asList(transaction.get("outputs")).get(index));
    }
    private static Map<String, Object> proof(Map<String, Object> transaction) {
        return Json.asObject(input(transaction, 0).get("spendingProof"));
    }
    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected rejected recovery identity"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws IOException; }
    private static final class Server implements AutoCloseable {
        private final HttpServer server;
        Server(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", handler::handle);
            server.start();
        }
        NodeApi api() { return new NodeApi(server.getAddress().getPort(), KEY, () -> true); }
        @Override public void close() { server.stop(0); }
    }
}
