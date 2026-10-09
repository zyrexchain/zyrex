package org.zyrexchain.desktop;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Explicitly invoked funded test: native signing through the real GUI, with no mining or confirmation claim. */
public final class FundedGuiSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static WalletWindow window;

    private FundedGuiSmoke() { }

    public static void main(String[] args) throws Exception {
        if ((args.length != 6 && args.length != 7) || GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException(
                "FundedGuiSmoke DATA_ROOT OUTPUT_DIRECTORY JAVA NODE_JAR PRIVATE_PHRASE_FILE EXPECTED_SENDER [AMOUNT_NANO]");
        }
        Path root = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path java = Path.of(args[2]);
        Path nodeJar = Path.of(args[3]);
        String expectedSender = args[5];
        NodeApi.validateAddress(expectedSender);
        long amount = args.length == 7 ? Long.parseLong(args[6]) : 1_000_000L;
        long fee = Units.DEFAULT_FEE;
        if (amount <= 0 || amount > 1_000_000_000L) {
            throw new IllegalArgumentException("Funded verification must use a bounded positive testnet amount up to 1 ZYRX");
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
            NodeManager managedSender = sender;
            edt(() -> {
                Ui.install();
                window = new WalletWindow(managedSender);
                window.setVisible(true);
                window.start();
                return null;
            });
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
            NodeManager managedReceiver = receiver;
            await(() -> mempoolContains(managedSender, transactionId), "sender native mempool acceptance");
            await(() -> mempoolContains(managedReceiver, transactionId), "receiver native mempool propagation");
            Map<String, Object> nativeTx = Json.asObject(read(sender, "/transactions/unconfirmed/byTransactionId/" + transactionId));
            Map<String, Object> script = Json.asObject(read(receiver, "/script/addressToTree/" + recipient));
            boolean matchingOutput = false;
            for (Object entry : Json.asList(nativeTx.get("outputs"))) {
                Map<String, Object> box = Json.asObject(entry);
                if (script.get("tree").equals(box.get("ergoTree")) && Json.asLong(box.get("value")) == amount) {
                    matchingOutput = true;
                }
            }
            check(matchingOutput, "Native signed transaction must contain the exact recipient output and integer amount");
            check(nativeTx.get("cost") instanceof Number && Json.asLong(nativeTx.get("cost")) > 0,
                "UTXO node must report actual successful script validation cost");
            check(!Json.asList(nativeTx.get("inputs")).isEmpty(), "Native signed payment must spend real inputs");
            for (Object input : Json.asList(nativeTx.get("inputs"))) {
                Map<String, Object> proof = Json.asObject(Json.asObject(input).get("spendingProof"));
                check(proof.get("proofBytes") instanceof String && !((String) proof.get("proofBytes")).isEmpty(),
                    "Every funded input must include its native cryptographic spending proof");
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
            report.put("nativeMempoolAccepted", true);
            report.put("propagatedToRecipientNode", true);
            report.put("nativeScriptValidationCost", nativeTx.get("cost"));
            report.put("blockConfirmed", false);
            report.put("genesis", sender.api().info().get("genesisBlockId"));
            report.put("height", sender.api().info().get("fullHeight"));
            Files.writeString(output.resolve("funded-gui-verification.json"), Json.stringify(report) + "\n", StandardCharsets.UTF_8);
            await(() -> enabled("unlock-wallet"), "completed signing request before shutdown");
            SwingUtilities.invokeLater(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
            await(() -> !inspect(() -> window.isDisplayable()), "graceful funded wallet shutdown");
            check(!sender.isRunning(), "Desktop shutdown must stop its owned full node");
            System.out.println("Funded GUI signing and native mempool propagation passed; no block confirmation is claimed.");
        } finally {
            Arrays.fill(phrase, '\0');
            Arrays.fill(senderPassword, '\0');
            if (receiverPhrase != null) Arrays.fill(receiverPhrase, '\0');
            Arrays.fill(receiverPassword, '\0');
            if (sender != null) sender.close();
            if (receiver != null) receiver.close();
            edt(() -> { for (Window candidate : Window.getWindows()) candidate.dispose(); return null; });
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
            throw new IOException("Managed node public read failed with HTTP " + response.statusCode());
        }
        return Json.parse(response.body());
    }

    private static boolean mempoolContains(NodeManager manager, String id) {
        try {
            for (Object entry : Json.asList(read(manager, "/transactions/unconfirmed?limit=100&offset=0"))) {
                if (id.equals(Json.asObject(entry).get("id"))) return true;
            }
        } catch (Exception ignored) { }
        return false;
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
