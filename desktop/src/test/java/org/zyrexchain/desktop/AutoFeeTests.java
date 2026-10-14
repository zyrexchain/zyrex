package org.zyrexchain.desktop;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.ergoplatform.ZyrexAddressEncoder;

/** Fee preparation, exact signed transaction recovery and Swing input regressions. */
public final class AutoFeeTests {
    private static final String KEY = "0123456789abcdef".repeat(4);
    private static final String ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94";
    private static final String ID = "f".repeat(64);
    private static final String TREE = ZyrexAddressEncoder.apply((byte) 64).fromString(ADDRESS).get().script().bytesHex();
    private static int passed;

    private AutoFeeTests() { }

    public static void main(String[] args) throws Exception {
        automaticUsesSignedSizeAndPreviewsBeforeBroadcast();
        manualDoesNotReprice();
        unsupportedQuotesUseFloorButFailuresRemainFailures();
        invalidQuotesNeverBroadcast();
        repricingIsBoundedAndOverflowIsRejected();
        automaticFeeLimitRequiresAnExplicitManualOverride();
        editedPreviewIsRejectedBeforeConfirmation();
        exactIntentRejectsExternalDuplicatesAndAssetTransfers();
        ownedAssetChangeAndSelfTransfersRemainValid();
        signedRecoveryPreservesIdentityAndOutputs();
        interruptedLargePrivateTemporaryRecordDoesNotBlockRecovery();
        retryObservationsAvoidDuplicateBroadcast();
        formDefaultsToAutoAndPreservesManualValidation();
        System.out.println("Desktop automatic fees: " + passed + " scenarios passed");
    }

    private static void automaticUsesSignedSizeAndPreviewsBeforeBroadcast() throws Exception {
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger broadcasts = new AtomicInteger();
        AtomicReference<Map<String, Object>> last = new AtomicReference<>();
        try (Server server = new Server(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (identity(exchange)) return;
            check(KEY.equals(exchange.getRequestHeaders().getFirst("api_key")), "Private fee/signing operations need authentication");
            if (path.equals("/wallet/transaction/generate")) {
                long fee = Json.asLong(body(exchange).get("fee"));
                generated.incrementAndGet();
                last.set(transaction(fee));
                reply(exchange, 200, last.get());
            } else if (path.equals("/transactions/feeQuote")) {
                Map<String, Object> tx = body(exchange);
                check(Json.stringify(tx).equals(Json.stringify(last.get())), "Quote uses the signed transaction, not an invented size hint");
                reply(exchange, 200, quote(2_000_000L, fee(tx), 271));
            } else if (path.equals("/transactions/check")) {
                check(Json.stringify(body(exchange)).equals(Json.stringify(last.get())), "Check binds the final signed transaction");
                reply(exchange, 200, ID);
            } else if (path.equals("/transactions")) {
                broadcasts.incrementAndGet();
                check(Json.stringify(body(exchange)).equals(Json.stringify(last.get())), "Broadcast uses the exact previewed signed transaction");
                reply(exchange, 200, ID);
            } else throw new AssertionError("Unexpected route " + path);
        })) {
            NodeApi api = server.api();
            PreparedTransfer prepared = api.prepareTransfer(ADDRESS, 2_000_000_001L, 0);
            check(prepared.fee == 2_000_000L && prepared.size == 271 && generated.get() == 2, "Exact quote reprices once");
            check(broadcasts.get() == 0, "Preparation never broadcasts before explicit confirmation");
            prepared.transaction().clear();
            check(api.broadcastPrepared(prepared).equals(ID), "Copies cannot mutate the stored signed transaction");
            check(broadcasts.get() == 1 && generated.get() == 2, "Broadcast never asks the node to sign again");
        }
        passed++;
    }

    private static void manualDoesNotReprice() throws Exception {
        AtomicInteger quotes = new AtomicInteger();
        try (Server server = simpleServer(503, quotes)) {
            PreparedTransfer prepared = server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 3_000_000L);
            check(prepared.fee == 3_000_000L && quotes.get() == 0 && prepared.feeBasis.equals("Manual fee"), "Manual fee is preserved exactly");
        }
        passed++;
    }

    private static void unsupportedQuotesUseFloorButFailuresRemainFailures() throws Exception {
        for (int status : new int[] {404, 405}) {
            try (Server server = simpleServer(status, new AtomicInteger())) {
                PreparedTransfer prepared = server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 0);
                check(prepared.fee == Units.DEFAULT_FEE && prepared.feeBasis.contains("older node"), "Older nodes retain safe minimum compatibility");
            }
        }
        for (int status : new int[] {400, 401, 500}) {
            try (Server server = simpleServer(status, new AtomicInteger())) {
                rejects(() -> server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 0));
            }
        }
        passed++;
    }

    private static void invalidQuotesNeverBroadcast() throws Exception {
        for (String field : new String[] {"txId", "paidFeeNano", "feeScriptHex", "quoteVersion", "txSize", "feeNano"}) {
            try (Server server = new Server(exchange -> {
                if (identity(exchange)) return;
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/wallet/transaction/generate")) reply(exchange, 200, transaction(Units.DEFAULT_FEE));
                else if (path.equals("/transactions/feeQuote")) {
                    Map<String, Object> invalid = quote(Units.DEFAULT_FEE, Units.DEFAULT_FEE, 100);
                    invalid.put(field, field.equals("txSize") ? 98_305 : field.equals("quoteVersion") ? 2 : "invalid");
                    reply(exchange, 200, invalid);
                } else throw new AssertionError("Malformed quote must not reach validation or broadcast");
            })) { rejects(() -> server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 0)); }
        }
        passed++;
    }

    private static void repricingIsBoundedAndOverflowIsRejected() throws Exception {
        AtomicInteger generates = new AtomicInteger();
        try (Server server = new Server(exchange -> {
            if (identity(exchange)) return;
            if (exchange.getRequestURI().getPath().equals("/wallet/transaction/generate")) {
                generates.incrementAndGet();
                reply(exchange, 200, transaction(Json.asLong(body(exchange).get("fee"))));
            } else if (exchange.getRequestURI().getPath().equals("/transactions/feeQuote")) {
                long paid = fee(body(exchange));
                reply(exchange, 200, quote(paid + Units.DEFAULT_FEE, paid, 100));
            } else throw new AssertionError("An unstable quote must not reach broadcast");
        })) {
            rejects(() -> server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 0));
            check(generates.get() == 4, "Fee construction is bounded to four signing attempts");
            rejects(() -> server.api().prepareTransfer(ADDRESS, Long.MAX_VALUE, Units.DEFAULT_FEE));
            check(generates.get() == 4, "Overflow is rejected before requesting a signature");
        }
        passed++;
    }

    private static void signedRecoveryPreservesIdentityAndOutputs() throws Exception {
        Path home = Files.createTempDirectory("zyrex-prepared-transfer-");
        PendingTransferStore store = new PendingTransferStore(home);
        PreparedTransfer prepared = new PreparedTransfer(ADDRESS, 2_000_000_001L, Units.DEFAULT_FEE,
            transaction(Units.DEFAULT_FEE), "Manual fee", 0);
        store.save(prepared, ADDRESS);
        Path file = home.resolve("prepared-transfer.json");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")), "Recovery records are owner-only");
        }
        check(store.load(List.of(ADDRESS)).transactionJson.equals(prepared.transactionJson), "Recovery preserves the exact signed JSON");
        rejects(() -> store.save(prepared, ADDRESS));
        rejects(() -> store.load(List.of()));
        Map<String, Object> data = Json.asObject(Json.parse(Files.readString(file)));
        data.put("amount", 99);
        Files.writeString(file, Json.stringify(data));
        rejects(() -> store.load(List.of(ADDRESS)));
        check(Files.exists(file), "Invalid recovery records are preserved for inspection");
        Files.delete(file);
        Files.delete(home);
        passed++;
    }

    private static void automaticFeeLimitRequiresAnExplicitManualOverride() throws Exception {
        try (Server server = new Server(exchange -> {
            if (identity(exchange)) return;
            if (exchange.getRequestURI().getPath().equals("/wallet/transaction/generate")) {
                reply(exchange, 200, transaction(Json.asLong(body(exchange).get("fee"))));
            } else if (exchange.getRequestURI().getPath().equals("/transactions/feeQuote")) {
                reply(exchange, 200, quote(Units.AUTO_FEE_LIMIT + 1, fee(body(exchange)), 100));
            } else if (exchange.getRequestURI().getPath().equals("/transactions/check")) reply(exchange, 200, ID);
            else throw new AssertionError("An excessive Auto fee cannot broadcast");
        })) {
            rejects(() -> server.api().prepareTransfer(ADDRESS, 2_000_000_001L, 0));
            PreparedTransfer manual = server.api().prepareTransfer(ADDRESS, 2_000_000_001L, Units.AUTO_FEE_LIMIT + 1);
            check(manual.fee == Units.AUTO_FEE_LIMIT + 1, "An explicitly entered manual fee can override the Auto ceiling");
        }
        passed++;
    }

    private static void editedPreviewIsRejectedBeforeConfirmation() throws Exception {
        Path home = Files.createTempDirectory("zyrex-edited-preview-");
        try (Server server = simpleServer(404, new AtomicInteger())) {
            SwingUtilities.invokeAndWait(() -> {
                SendPanel panel = new SendPanel((address, amount, fee, success) -> { });
                field(panel, "send-amount").setText("2.000000001");
                SendController.Actions actions = new SendController.Actions() {
                    @Override public <T> void run(String description, java.util.concurrent.Callable<T> work,
                            java.util.function.Consumer<T> success) {
                        try {
                            T prepared = work.call();
                            field(panel, "send-amount").setText("3");
                            success.accept(prepared);
                        } catch (Exception failure) { throw new AssertionError("Preparation regression failed", failure); }
                    }
                };
                SendController controller = new SendController(panel, panel, new WalletHistory(home), new PendingTransferStore(home),
                    server::api, () -> List.of(ADDRESS), () -> true, () -> 5_000_000_000L, actions);
                controller.review(ADDRESS, 2_000_000_001L, 0, id -> { throw new AssertionError("Edited preview must never submit"); });
                check(((javax.swing.JTextArea) find(panel, "send-result")).getText().contains("form or wallet state changed"),
                    "An edited form invalidates the asynchronous native preview before a confirmation dialog");
            });
            check(!Files.exists(home.resolve("prepared-transfer.json")), "An invalidated preview is neither persisted nor broadcast");
        }
        Files.delete(home);
        passed++;
    }

    private static void retryObservationsAvoidDuplicateBroadcast() throws Exception {
        AtomicInteger broadcasts = new AtomicInteger();
        try (Server server = new Server(exchange -> {
            if (identity(exchange)) return;
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/wallet/transactionById")) reply(exchange, 404, Map.of());
            else if (path.equals("/transactions/unconfirmed/byTransactionId/" + ID)) reply(exchange, 200, transaction(Units.DEFAULT_FEE));
            else { broadcasts.incrementAndGet(); throw new AssertionError("History observation does not send another transaction"); }
        })) {
            check(server.api().observedTransaction(ID) && broadcasts.get() == 0, "Observed transaction requires no resend");
        }
        passed++;
    }

    private static void interruptedLargePrivateTemporaryRecordDoesNotBlockRecovery() throws Exception {
        Path home = Files.createTempDirectory("zyrex-interrupted-recovery-");
        PendingTransferStore store = new PendingTransferStore(home);
        Map<String, Object> tx = transaction(Units.DEFAULT_FEE);
        tx.put("metadataPadding", "x".repeat(20_000));
        PreparedTransfer prepared = new PreparedTransfer(ADDRESS, 2_000_000_001L, Units.DEFAULT_FEE, tx, "Manual fee", 0);
        store.save(prepared, ADDRESS);
        Path file = home.resolve("prepared-transfer.json");
        Path temporary = home.resolve("prepared-transfer.json.new");
        Files.move(file, temporary);
        check(Files.size(temporary) > 16_384, "Interrupted record exceeds the small settings-file bound");
        store.save(prepared, ADDRESS);
        check(store.load(List.of(ADDRESS)).transactionJson.equals(prepared.transactionJson), "A stale large private temporary is safely replaced");
        check(!Files.exists(temporary), "Completed atomic save removes its leftover temporary");
        store.clear(prepared, List.of(ADDRESS));
        Files.delete(home);
        passed++;
    }

    private static void exactIntentRejectsExternalDuplicatesAndAssetTransfers() throws Exception {
        String ownedAddress = "ZRXAcpXPpymBbrCR8RAzK8RDSr1gBYgShycfBW2oAsgjDHFUA8dADzj";
        for (String attack : new String[] {"duplicate", "external", "recipient-assets", "fee-assets"}) {
            Map<String, Object> tx = transaction(Units.DEFAULT_FEE);
            List<Object> outputs = new java.util.ArrayList<>(Json.asList(tx.get("outputs")));
            if (attack.equals("duplicate")) outputs.add(Json.object("value", 2_000_000_001L, "ergoTree", TREE));
            else if (attack.equals("external")) outputs.add(Json.object("value", 123, "ergoTree", "1000"));
            else {
                Map<String, Object> target = Json.asObject(outputs.get(attack.equals("recipient-assets") ? 0 : 1));
                target.put("assets", List.of(Json.object("tokenId", "c".repeat(64), "amount", 1)));
            }
            tx.put("outputs", outputs);
            rejects(() -> new PreparedTransfer(ADDRESS, 2_000_000_001L, Units.DEFAULT_FEE, tx, "Manual fee", 0, List.of(ownedAddress)));
        }
        passed++;
    }

    private static void ownedAssetChangeAndSelfTransfersRemainValid() throws Exception {
        String ownedAddress = "ZRXAcpXPpymBbrCR8RAzK8RDSr1gBYgShycfBW2oAsgjDHFUA8dADzj";
        String ownedTree = ZyrexAddressEncoder.apply((byte) 64).fromString(ownedAddress).get().script().bytesHex();
        Map<String, Object> tx = transaction(Units.DEFAULT_FEE);
        List<Object> outputs = new java.util.ArrayList<>(Json.asList(tx.get("outputs")));
        outputs.add(Json.object("value", 123, "ergoTree", ownedTree,
            "assets", List.of(Json.object("tokenId", "c".repeat(64), "amount", 5))));
        tx.put("outputs", outputs);
        new PreparedTransfer(ADDRESS, 2_000_000_001L, Units.DEFAULT_FEE, tx, "Manual fee", 0, List.of(ownedAddress));
        outputs.set(2, Json.object("value", 2_000_000_001L, "ergoTree", TREE,
            "assets", List.of(Json.object("tokenId", "c".repeat(64), "amount", 5))));
        new PreparedTransfer(ADDRESS, 2_000_000_001L, Units.DEFAULT_FEE, tx, "Manual fee", 0, List.of(ADDRESS));
        passed++;
    }

    private static void formDefaultsToAutoAndPreservesManualValidation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<Long> requested = new AtomicReference<>();
            SendPanel panel = new SendPanel((address, amount, fee, success) -> requested.set(fee));
            panel.setReady(true, "", 5_000_000_000L);
            field(panel, "send-recipient").setText(ADDRESS);
            field(panel, "send-amount").setText("1");
            check(field(panel, "send-fee").getText().equals("auto"), "Auto is the default");
            ((JButton) find(panel, "send-review")).doClick(0);
            check(requested.get() == 0, "Auto remains explicit, not a disguised fixed manual fee");
            long before = panel.reviewRevision();
            field(panel, "send-fee").setText("0.003");
            check(panel.reviewRevision() > before, "Editing the form invalidates its previous review revision");
            ((JButton) find(panel, "send-review")).doClick(0);
            check(requested.get() == 3_000_000L, "Manual override keeps integer precision");
            for (String invalid : new String[] {"0.0009", "0", "0.000000000"}) {
                requested.set(null);
                field(panel, "send-fee").setText(invalid);
                ((JButton) find(panel, "send-review")).doClick(0);
                check(requested.get() == null, "An underpriced or zero manual fee cannot select Auto");
            }
        });
        passed++;
    }

    private static Server simpleServer(int quoteStatus, AtomicInteger quotes) throws IOException {
        return new Server(exchange -> {
            if (identity(exchange)) return;
            switch (exchange.getRequestURI().getPath()) {
                case "/wallet/transaction/generate": reply(exchange, 200, transaction(Json.asLong(body(exchange).get("fee")))); break;
                case "/transactions/feeQuote": quotes.incrementAndGet(); reply(exchange, quoteStatus, Map.of()); break;
                case "/transactions/check": reply(exchange, 200, ID); break;
                default: throw new AssertionError("Unexpected broadcast during preparation");
            }
        });
    }

    private static Map<String, Object> transaction(long fee) {
        return Json.object("id", ID, "inputs", List.of(Json.object("boxId", "a".repeat(64))), "dataInputs", List.of(),
            "outputs", List.of(Json.object("value", 2_000_000_001L, "ergoTree", TREE),
                Json.object("value", fee, "ergoTree", WalletHistory.feeTree())));
    }

    private static long fee(Map<String, Object> tx) { return Json.asLong(Json.asObject(Json.asList(tx.get("outputs")).get(1)).get("value")); }

    private static Map<String, Object> quote(long recommended, long paid, int size) {
        return Json.object("quoteVersion", 1, "feeNano", Long.toString(recommended), "minimumFeeNano", "1000000", "txSize", size,
            "maxTransactionSize", 98304, "txId", ID, "basis", "recent-inclusions", "paidFeeNano", Long.toString(paid),
            "feeScriptHex", WalletHistory.feeTree());
    }

    private static boolean identity(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/info")) return false;
        reply(exchange, 200, Json.object("network", "testnet", "genesisBlockId", NodeApi.TESTNET_GENESIS));
        return true;
    }

    private static Map<String, Object> body(HttpExchange exchange) throws IOException {
        return Json.asObject(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static JTextField field(Container root, String name) { return (JTextField) find(root, name); }
    private static Component find(Container root, String name) {
        for (Component component : root.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container) { Component found = find((Container) component, name); if (found != null) return found; }
        }
        return null;
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void rejects(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected rejected operation"); }
        catch (IOException | IllegalArgumentException failure) { }
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
