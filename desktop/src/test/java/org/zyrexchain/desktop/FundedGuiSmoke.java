package org.zyrexchain.desktop;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Explicit funded GUI regression using isolated wallets and actual native block inclusion. */
public final class FundedGuiSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final int MAX_CONFIRM_WAIT_SECONDS = 480;
    private static final String FEE_TREE = "1005040004000e351002040a08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959"
        + "f2815b16f81798ea02d192a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a573010074730273038301"
        + "08cdeeac93b1a57304";
    private static WalletWindow window;

    private FundedGuiSmoke() { }

    public static void main(String[] args) throws Exception {
        if ((args.length < 6 || args.length > 8) || GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException(
                "FundedGuiSmoke DATA_ROOT OUTPUT_DIRECTORY JAVA NODE_JAR PRIVATE_PHRASE_FILE EXPECTED_SENDER"
                    + " [AMOUNT_NANO [CONFIRM_WAIT_SECONDS]]");
        }
        Path root = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path java = Path.of(args[2]);
        Path nodeJar = Path.of(args[3]);
        String expectedSender = args[5];
        NodeApi.validateAddress(expectedSender);
        long amount = args.length >= 7 ? Long.parseLong(args[6]) : 1_000_000L;
        int confirmWait = args.length == 8 ? Integer.parseInt(args[7]) : MAX_CONFIRM_WAIT_SECONDS;
        long fee = Units.DEFAULT_FEE;
        if (amount <= 0 || amount > 1_000_000_000L) {
            throw new IllegalArgumentException("Funded verification must use a bounded positive testnet amount up to 1 ZYRX");
        }
        if (confirmWait <= 0 || confirmWait > MAX_CONFIRM_WAIT_SECONDS) {
            throw new IllegalArgumentException("Confirmation wait must be between 1 and 480 seconds");
        }
        Files.createDirectories(root);
        Files.createDirectories(output);
        char[] phrase = privatePhrase(Path.of(args[4]));
        char[] senderPassword = randomPassword();
        char[] receiverPhrase = null;
        char[] receiverPassword = randomPassword();
        NodeManager sender = null;
        NodeManager receiver = null;
        try {
            sender = new NodeManager(fresh(root.resolve("sender")), java, nodeJar);
            receiver = new NodeManager(fresh(root.resolve("receiver")), java, nodeJar);
            receiver.start();
            receiver.awaitReady(TIMEOUT);
            receiverPhrase = NodeApi.createRecoveryPhrase().toCharArray();
            receiver.api().restore(new String(receiverPhrase), new String(receiverPassword));
            Arrays.fill(receiverPhrase, '\0');
            Arrays.fill(receiverPassword, '\0');
            String recipient = receiver.api().addresses().get(0);
            NodeApi.validateAddress(recipient);
            open(sender);
            await(() -> enabled("restore-wallet"), "funded wallet restore welcome screen");
            click("restore-wallet");
            text("restore-phrase", new String(phrase));
            text("restore-password", new String(senderPassword));
            text("restore-confirmation", new String(senderPassword));
            click("restore-submit");
            await(() -> label("wallet-state").equals("Unlocked"), "funded native wallet restore and unlock");
            Arrays.fill(phrase, '\0');
            Arrays.fill(senderPassword, '\0');
            await(() -> expectedSender.equals(text("receive-address")), "expected funded wallet address");
            await(() -> {
                try { return Units.parse(label("confirmed-balance")) >= Math.addExact(amount, fee); }
                catch (IllegalArgumentException ignored) { return false; }
            }, "confirmed native funds after wallet rescan");
            long confirmedBefore = Json.asLong(sender.api().balance().get("balance"));
            check(text("restore-phrase").isEmpty(), "Recovery phrase must be erased from the restore form");
            check(text("restore-password").isEmpty() && text("restore-confirmation").isEmpty(),
                "Restore passwords must be erased before public screenshots");
            edt(() -> { ((JTabbedPane) named("wallet-tabs")).setSelectedIndex(2); return null; });
            text("send-recipient", recipient);
            text("send-amount", Units.format(amount));
            text("send-fee", Units.format(fee));
            await(() -> enabled("send-review"), "funded synchronized transaction form");
            click("send-review");
            await(() -> visibleDialog("Confirm transaction"), "explicit amount, recipient and fee confirmation");
            confirmSend();
            await(() -> text("sent-transaction").matches("[0-9a-f]{64}"), "actual signed native transaction ID");
            String transactionId = text("sent-transaction");
            selectHistory();
            await(() -> historyMatches(transactionId, amount, fee, false), "outgoing GUI history with exact amount and fee");
            String initialHistoryStatus = historyValue(transactionId, "Status");
            Observation senderObservation = awaitObservation(sender, transactionId);
            Observation receiverObservation = awaitObservation(receiver, transactionId);
            Map<String, Object> nativeTx = senderObservation.transaction;
            Map<String, Object> script = Json.asObject(read(receiver, "/script/addressToTree/" + recipient));
            verifySignedPayment(nativeTx, (String) script.get("tree"), amount, fee);
            if (senderObservation.mempool) {
                check(nativeTx.get("cost") instanceof Number && Json.asLong(nativeTx.get("cost")) > 0,
                    "UTXO mempool must report actual successful script validation cost");
            }
            screenshot(output.resolve("signed-payment.png"));
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("guiRestoreFundedWallet", true);
            report.put("guiReviewedAndSignedPayment", true);
            report.put("sender", expectedSender);
            report.put("recipient", recipient);
            report.put("transactionId", transactionId);
            report.put("amountNano", Long.toString(amount));
            report.put("feeNano", Long.toString(fee));
            report.put("confirmedBalanceBeforeNano", Long.toString(confirmedBefore));
            report.put("senderHistoryImmediatelyVisible", true);
            report.put("senderHistoryInitialStatus", initialHistoryStatus);
            report.put("senderNativeObservation", senderObservation.source());
            report.put("receiverNativeObservation", receiverObservation.source());
            report.put("nativeMempoolAccepted", senderObservation.mempool);
            report.put("receiverNativeMempoolObserved", receiverObservation.mempool);
            report.put("propagatedToRecipientNode", true);
            report.put("nativeScriptValidationCost", nativeTx.get("cost"));
            report.put("nativeScriptValidationCostObserved", senderObservation.mempool);
            report.put("blockConfirmed", false);
            report.put("genesis", sender.api().info().get("genesisBlockId"));
            report.put("height", sender.api().info().get("fullHeight"));
            report.put("confirmationWaitLimitSeconds", confirmWait);
            writeReport(output, report);
            long confirmationDeadline = System.nanoTime() + Duration.ofSeconds(confirmWait).toNanos();
            Inclusion included = awaitInclusion(sender, transactionId, Duration.ofSeconds(confirmWait));
            Duration remainingConfirmationWait = Duration.ofNanos(Math.max(1, confirmationDeadline - System.nanoTime()));
            Inclusion received = awaitInclusion(receiver, transactionId, remainingConfirmationWait);
            check(included.height == received.height && included.blockId.equals(received.blockId),
                "Sender and receiver must prove the same canonical transaction inclusion");
            verifySignedPayment(included.transaction, (String) script.get("tree"), amount, fee);
            check(exactOwnedOutput(received.walletTransaction, recipient, amount),
                "Recipient native wallet history must own the exact payment output");
            check(Json.asLong(receiver.api().balance().get("balance")) == amount,
                "Fresh recipient wallet must hold exactly the confirmed payment amount");
            await(() -> historyMatches(transactionId, amount, fee, true), "confirmed outgoing GUI history");
            check(historyValue(transactionId, "Status").startsWith("Included · block " + included.height + " · "),
                "GUI history inclusion height must match the canonical native block");
            screenshot(output.resolve("confirmed-history.png"));
            report.put("blockConfirmed", true);
            report.put("inclusionHeight", included.height);
            report.put("canonicalBlockId", included.blockId);
            report.put("canonicalInclusionMatchedRecipientNode", true);
            report.put("recipientExactConfirmedOwnedOutput", true);
            report.put("senderHistoryConfirmed", true);
            report.put("nativeValidationEvidence", senderObservation.mempool ? "mempool script cost and canonical block"
                : "canonical block; transaction included before mempool observation");
            writeReport(output, report);
            closeWindow();
            check(!sender.isRunning(), "Desktop shutdown must stop its owned full node");
            Path senderHome = sender.dataHome();
            sender = new NodeManager(senderHome, java, nodeJar);
            open(sender);
            await(() -> label("wallet-state").equals("Locked"), "restarted encrypted native wallet");
            selectHistory();
            await(() -> historyMatches(transactionId, amount, fee, true), "confirmed outgoing history retained after restart");
            Inclusion restarted = awaitInclusion(sender, transactionId, TIMEOUT);
            check(included.height == restarted.height && included.blockId.equals(restarted.blockId),
                "Restarted sender must retain the same canonical native inclusion");
            screenshot(output.resolve("restarted-history.png"));
            closeWindow();
            check(!sender.isRunning(), "Restarted desktop shutdown must stop only its owned node");
            report.put("senderHistoryConfirmedAfterRestart", true);
            report.put("senderOwnedNodeStopped", true);
            receiver.close();
            check(!receiver.isRunning(), "Recipient shutdown must stop only its owned node");
            report.put("recipientOwnedNodeStopped", true);
            writeReport(output, report);
            System.out.println("Funded GUI signing, exact native payment, canonical confirmation and history restart persistence passed.");
        } finally {
            Arrays.fill(phrase, '\0');
            Arrays.fill(senderPassword, '\0');
            if (receiverPhrase != null) Arrays.fill(receiverPhrase, '\0');
            Arrays.fill(receiverPassword, '\0');
            try {
                if (sender != null) sender.close();
            } finally {
                try {
                    if (receiver != null) receiver.close();
                } finally {
                    edt(() -> { for (Window candidate : Window.getWindows()) candidate.dispose(); return null; });
                }
            }
        }
    }

    private static void open(NodeManager manager) throws Exception {
        edt(() -> {
            Ui.install();
            window = new WalletWindow(manager);
            window.setVisible(true);
            window.start();
            return null;
        });
    }

    private static void closeWindow() throws Exception {
        await(() -> enabled("unlock-wallet"), "completed wallet request before shutdown");
        WalletWindow closing = window;
        SwingUtilities.invokeLater(() -> closing.dispatchEvent(new WindowEvent(closing, WindowEvent.WINDOW_CLOSING)));
        await(() -> !inspect(closing::isDisplayable), "graceful funded wallet shutdown");
    }

    private static void selectHistory() throws Exception {
        edt(() -> {
            JTabbedPane tabs = (JTabbedPane) named("wallet-tabs");
            for (int index = 0; index < tabs.getTabCount(); index++) {
                if ("History".equals(tabs.getTitleAt(index))) {
                    tabs.setSelectedIndex(index);
                    return null;
                }
            }
            throw new AssertionError("Wallet must provide its History tab");
        });
    }

    private static String historyValue(String id, String columnName) {
        return inspect(() -> {
            JTable table = (JTable) named("wallet-history");
            javax.swing.table.TableModel model = table.getModel();
            int column = -1;
            for (int index = 0; index < model.getColumnCount(); index++) {
                if (columnName.equals(model.getColumnName(index))) column = index;
            }
            if (column < 0) throw new AssertionError("Missing history column: " + columnName);
            for (int row = 0; row < model.getRowCount(); row++) {
                if (id.equals(model.getValueAt(row, 0))) return String.valueOf(model.getValueAt(row, column));
            }
            return "";
        });
    }

    private static boolean historyMatches(String id, long amount, long fee, boolean included) {
        String status = historyValue(id, "Status");
        if (!"Sent".equals(historyValue(id, "Direction"))) return false;
        if (!Units.format(amount).equals(historyValue(id, "Sent / received · ZYRX"))) return false;
        if (!Units.format(fee).equals(historyValue(id, "Fee · ZYRX"))) return false;
        if (!Units.format(-Math.addExact(amount, fee)).equals(historyValue(id, "Net change · ZYRX"))) return false;
        if (included) return status.startsWith("Included · block ");
        return status.startsWith("Pending · ") || status.startsWith("Submitted · ") || status.startsWith("Included · block ");
    }

    private static void verifySignedPayment(Map<String, Object> transaction, String recipientTree, long amount, long fee) {
        boolean matchingOutput = false;
        boolean matchingFee = false;
        for (Object entry : Json.asList(transaction.get("outputs"))) {
            Map<String, Object> box = Json.asObject(entry);
            if (recipientTree.equals(box.get("ergoTree")) && Json.asLong(box.get("value")) == amount) matchingOutput = true;
            if (FEE_TREE.equals(box.get("ergoTree")) && Json.asLong(box.get("value")) == fee) matchingFee = true;
        }
        check(matchingOutput, "Native signed transaction must contain the exact recipient output and integer amount");
        check(matchingFee, "Native signed transaction must contain the exact canonical fee output");
        check(!Json.asList(transaction.get("inputs")).isEmpty(), "Native signed payment must spend real inputs");
        for (Object input : Json.asList(transaction.get("inputs"))) {
            Map<String, Object> proof = Json.asObject(Json.asObject(input).get("spendingProof"));
            check(proof.get("proofBytes") instanceof String && !((String) proof.get("proofBytes")).isEmpty(),
                "Every funded input must include its native cryptographic spending proof");
        }
    }

    private static boolean exactOwnedOutput(Map<String, Object> transaction, String recipient, long amount) {
        for (Object entry : Json.asList(transaction.get("outputs"))) {
            Map<String, Object> box = Json.asObject(entry);
            if (recipient.equals(box.get("address")) && Json.asLong(box.get("value")) == amount) return true;
        }
        return false;
    }

    private static Observation awaitObservation(NodeManager manager, String id) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Map<String, Object> pending = Json.asObject(read(manager, "/transactions/unconfirmed/byTransactionId/" + id));
                check(id.equals(pending.get("id")), "Native mempool lookup must return the requested transaction");
                return new Observation(pending, true);
            } catch (NativeReadException error) {
                if (error.statusCode != 404) throw error;
            }
            Map<String, Object> transaction = walletTransaction(manager, id);
            if (transaction != null && Json.asLong(transaction.get("inclusionHeight")) > 0) {
                Inclusion inclusion = canonicalInclusion(manager, id, transaction);
                if (inclusion != null) return new Observation(inclusion.transaction, false);
            }
            Thread.sleep(300);
        }
        throw new AssertionError("Timed out waiting for native mempool or canonical inclusion of the signed transaction");
    }

    private static Map<String, Object> walletTransaction(NodeManager manager, String id) throws Exception {
        for (Map<String, Object> transaction : manager.api().transactions()) {
            if (id.equals(transaction.get("id"))) return transaction;
        }
        return null;
    }

    private static Inclusion awaitInclusion(NodeManager manager, String id, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> transaction = walletTransaction(manager, id);
            if (transaction != null && Json.asLong(transaction.get("inclusionHeight")) > 0) {
                Inclusion inclusion = canonicalInclusion(manager, id, transaction);
                if (inclusion != null) return inclusion;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("No canonical block inclusion was observed before the bounded confirmation deadline");
    }

    private static Inclusion canonicalInclusion(NodeManager manager, String id, Map<String, Object> walletTx) throws Exception {
        long height = Json.asLong(walletTx.get("inclusionHeight"));
        check(height > 0 && height <= Json.asLong(manager.api().info().get("fullHeight")),
            "Native wallet inclusion height must refer to an applied full block");
        List<Object> ids = Json.asList(read(manager, "/blocks/at/" + height));
        if (ids.isEmpty()) return null;
        String blockId = (String) ids.get(0);
        Map<String, Object> section = Json.asObject(read(manager, "/blocks/" + blockId + "/transactions"));
        check(blockId.equals(section.get("headerId")), "Native transaction section must identify its canonical header");
        for (Object entry : Json.asList(section.get("transactions"))) {
            Map<String, Object> transaction = Json.asObject(entry);
            if (id.equals(transaction.get("id"))) return new Inclusion(height, blockId, transaction, walletTx);
        }
        return null;
    }

    private static void writeReport(Path output, Map<String, Object> report) throws IOException {
        Path target = output.resolve("funded-gui-verification.json");
        Files.writeString(target, Json.stringify(report) + "\n", StandardCharsets.UTF_8);
        if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(target, java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    private static final class Observation {
        final Map<String, Object> transaction;
        final boolean mempool;

        Observation(Map<String, Object> transaction, boolean mempool) {
            this.transaction = transaction;
            this.mempool = mempool;
        }

        String source() { return mempool ? "native mempool" : "canonical block before mempool observation"; }
    }

    private static final class Inclusion {
        final long height;
        final String blockId;
        final Map<String, Object> transaction;
        final Map<String, Object> walletTransaction;

        Inclusion(long height, String blockId, Map<String, Object> transaction, Map<String, Object> walletTransaction) {
            this.height = height;
            this.blockId = blockId;
            this.transaction = transaction;
            this.walletTransaction = walletTransaction;
        }
    }

    private static Path fresh(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Funded verification never resets existing wallet data");
        return path;
    }

    private static char[] privatePhrase(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("The recovery phrase input must be a private regular file");
        }
        if (!Files.getFileStore(path).supportsFileAttributeView("posix")) {
            throw new IOException("This explicitly funded test requires a file system with verifiable private file permissions");
        }
        for (PosixFilePermission permission : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)) {
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")) {
                throw new IOException("The recovery phrase file must be readable only by its owner");
            }
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() == 0 || channel.size() > 16384) throw new IOException("The recovery phrase file has an invalid size");
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            String mnemonic = StandardCharsets.UTF_8.decode((ByteBuffer) buffer.flip()).toString().strip();
            Arrays.fill(buffer.array(), (byte) 0);
            return NodeApi.validateMnemonic(mnemonic).toCharArray();
        }
    }

    private static char[] randomPassword() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] password = Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        Arrays.fill(random, (byte) 0);
        return password;
    }

    private static Object read(NodeManager manager, String path) throws Exception {
        manager.api().info();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
            }).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + manager.apiPort() + path))
            .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body().length() > 2_000_000) {
            throw new NativeReadException(response.statusCode());
        }
        return Json.parse(response.body());
    }

    private static final class NativeReadException extends IOException {
        private static final long serialVersionUID = 1L;
        final int statusCode;

        NativeReadException(int statusCode) {
            super("Managed node public read failed with HTTP " + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static void screenshot(Path path) throws Exception {
        Thread.sleep(300);
        Rectangle bounds = edt(() -> window.getBounds());
        ImageIO.write(new Robot().createScreenCapture(bounds), "png", path.toFile());
    }

    private static void confirmSend() {
        SwingUtilities.invokeLater(() -> {
            for (Window candidate : Window.getWindows()) {
                if (candidate instanceof JDialog && ((JDialog) candidate).getTitle().equals("Confirm transaction")) {
                    Component button = byText(candidate, "Send ZYRX");
                    if (button instanceof AbstractButton) ((AbstractButton) button).doClick(0);
                }
            }
        });
    }

    private static Component byText(Component component, String value) {
        if (component instanceof AbstractButton && value.equals(((AbstractButton) component).getText())) return component;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Component found = byText(child, value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean visibleDialog(String title) {
        return inspect(() -> {
            for (Window candidate : Window.getWindows()) {
                if (candidate instanceof JDialog && ((JDialog) candidate).getTitle().equals(title) && candidate.isShowing()) return true;
            }
            return false;
        });
    }

    private static void text(String name, String value) throws Exception {
        edt(() -> { ((JTextComponent) named(name)).setText(value); return null; });
    }

    private static String text(String name) { return inspect(() -> ((JTextComponent) named(name)).getText()); }
    private static String label(String name) { return inspect(() -> ((JLabel) named(name)).getText()); }
    private static boolean enabled(String name) { return inspect(() -> named(name).isEnabled() && named(name).isShowing()); }
    private static void click(String name) { SwingUtilities.invokeLater(() -> ((AbstractButton) named(name)).doClick(0)); }

    private static Component named(String name) {
        for (Window candidate : Window.getWindows()) {
            if (!candidate.isDisplayable()) continue;
            Component found = namedIn(candidate, name);
            if (found != null) return found;
        }
        throw new AssertionError("Missing GUI component: " + name);
    }

    private static Component namedIn(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Component found = namedIn(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(150);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static <T> T edt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { result.set(operation.call()); }
            catch (Exception error) { failure.set(error); }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static <T> T inspect(Callable<T> operation) {
        try { return edt(operation); }
        catch (Exception failure) { throw new AssertionError("Unable to inspect funded GUI state", failure); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
