package org.zyrexchain.desktop;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Actual GUI and native node integration; secrets exist only in this test process's memory. */
public final class GuiSmoke {
    private static final Duration TIMEOUT = Duration.ofSeconds(180);
    private static final String PASSWORD = "Zyrex integration password 2026";
    private static WalletWindow current;

    private GuiSmoke() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4 || GraphicsEnvironment.isHeadless()) {
            throw new IllegalArgumentException("GuiSmoke DATA_ROOT OUTPUT_DIRECTORY JAVA_EXECUTABLE NODE_JAR requires a desktop display");
        }
        Path home = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path java = Path.of(args[2]);
        Path nodeJar = Path.of(args[3]);
        Files.createDirectories(home);
        Files.createDirectories(output);
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Path image = nodeJar.getParent().getParent();
            Path copied = output.resolve("application folder \u03a9");
            copyImage(image, copied);
            Process version = new ProcessBuilder(copied.resolve("Zyrex.exe").toString(), "--version")
                .redirectErrorStream(true).redirectOutput(output.resolve("launcher-version.txt").toFile()).start();
            if (!version.waitFor(30, TimeUnit.SECONDS)) {
                version.destroyForcibly();
                throw new AssertionError("The installed launcher must run from a Unicode application directory");
            }
            check(version.exitValue() == 0, "The Unicode application launcher must exit successfully");
            java = copied.resolve("runtime/bin/java.exe");
            nodeJar = copied.resolve("app/zyrex.jar");
        }
        NodeManager createManager = null;
        NodeManager restoreManager = null;
        char[] ephemeralPhrase = null;
        try {
            createManager = new NodeManager(freshHome(home, "create"), java, nodeJar);
            open(createManager);
            await(() -> enabled("create-wallet"), "wallet creation welcome screen");
            click("create-wallet");
            text("create-password", PASSWORD);
            text("create-confirmation", PASSWORD);
            click("create-submit");
            await(() -> !readText("recovery-phrase").isEmpty(), "native recovery phrase");
            String display = readText("recovery-phrase");
            String[] words = display.replaceAll("\\d+\\.\\s*", "").strip().split("\\s+");
            check(words.length == 24, "Native wallet must produce its configured 24 word phrase");
            ephemeralPhrase = String.join(" ", words).toCharArray();
            NodeApi.validateMnemonic(new String(ephemeralPhrase));
            check(Boolean.FALSE.equals(createManager.api().status().get("isInitialized")),
                "Native encrypted wallet must not exist before recovery phrase acknowledgement");
            SwingUtilities.invokeLater(() -> current.dispatchEvent(new WindowEvent(current, WindowEvent.WINDOW_CLOSING)));
            await(() -> dialogVisible("Save your recovery phrase"), "unsaved recovery phrase close protection");
            closeMessage("Save your recovery phrase");
            check(edt(() -> current.isDisplayable()) && !readText("recovery-phrase").isEmpty(), "Unconfirmed backup must block close");
            text("backup-word-0", "wrong");
            text("backup-word-1", words[words.length / 2]);
            text("backup-word-2", words[words.length - 1]);
            edt(() -> { ((AbstractButton) component("backup-saved")).setSelected(true); return null; });
            click("backup-confirm");
            await(() -> readLabel("onboarding-error").startsWith("Check the requested words"), "incorrect backup words rejection");
            check(Boolean.FALSE.equals(createManager.api().status().get("isInitialized")),
                "An invalid backup confirmation must not create the native wallet");
            text("backup-word-0", words[0]);
            Arrays.fill(words, null);
            click("backup-confirm");
            await(() -> readLabel("wallet-state").equals("Unlocked"), "backed-up native wallet creation and unlock");
            check(readText("recovery-phrase").isEmpty(), "Acknowledged phrase must disappear from the UI");
            await(() -> readLabel("network-status").startsWith("Connected"), "public bootstrap synchronization");
            String firstAddress = readText("receive-address");
            NodeApi.validateAddress(firstAddress);
            check(firstAddress.startsWith("ZRX"), "Receive must show an actual Zyrex testnet address");
            selectTab(1);
            int previous = addressCount();
            click("receive-new");
            await(() -> addressCount() > previous, "native address derivation");
            NodeApi.validateAddress(readText("receive-address"));
            selectTab(2);
            text("send-recipient", firstAddress);
            text("send-amount", "0.000000001");
            check(Units.parse(readText("send-amount")) == 1, "One nano must retain exact precision in the send form");
            check(!enabled("send-review"), "An empty wallet must not permit a transaction review");
            reject(() -> Units.parse("0"), "Zero send amount must be rejected");
            reject(() -> NodeApi.validateAddress("9" + firstAddress.substring(3)), "Foreign addresses must be rejected");
            try {
                createManager.api().send(firstAddress, 1, Units.DEFAULT_FEE);
                throw new AssertionError("Native wallet accepted a payment without spendable coins");
            } catch (NodeApi.ApiException expected) {
                check(expected.statusCode() == 400, "Insufficient native balance must be rejected by consensus wallet validation");
            }
            click("unlock-wallet");
            await(() -> readLabel("wallet-state").equals("Locked"), "native wallet lock");
            click("unlock-wallet");
            await(() -> componentExists("unlock-password"), "second wallet unlock dialog");
            text("unlock-password", PASSWORD);
            click("unlock-submit");
            await(() -> readLabel("wallet-state").equals("Unlocked"), "second native wallet unlock");
            selectTab(0);
            capture(output.resolve("overview.png"));
            selectTab(4);
            capture(output.resolve("node.png"));
            closeWindow();
            check(!createManager.isRunning(), "Closing the desktop must stop its owned node");
            restoreManager = new NodeManager(freshHome(home, "restore"), java, nodeJar);
            open(restoreManager);
            await(() -> enabled("restore-wallet"), "wallet restore welcome screen");
            click("restore-wallet");
            text("restore-phrase", new String(ephemeralPhrase));
            text("restore-password", PASSWORD);
            text("restore-confirmation", PASSWORD);
            click("restore-submit");
            await(() -> readLabel("wallet-state").equals("Unlocked"), "native wallet recovery and rescan");
            await(() -> readLabel("network-status").startsWith("Connected"), "restored node synchronization");
            await(() -> firstAddress.equals(readText("receive-address")), "restored default address matches original wallet");
            Map<String, Object> info = restoreManager.api().info();
            Map<String, Object> status = restoreManager.api().status();
            check(NodeApi.TESTNET_GENESIS.equals(info.get("genesisBlockId")), "Restored node must use the public testnet genesis");
            check(((Number) status.get("walletHeight")).longValue() >= ((Number) info.get("fullHeight")).longValue(),
                "Restored wallet scan must catch up to native chain height");
            check(readText("restore-phrase").isEmpty(), "Submitted recovery phrase must be cleared from the UI");
            closeWindow();
            check(!restoreManager.isRunning(), "Recovered desktop node must stop on close");
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("guiCreate", true);
            report.put("backupChallenge", true);
            report.put("backupCloseProtection", true);
            report.put("addressDerivation", true);
            report.put("lockUnlock", true);
            report.put("integerSendValidation", true);
            report.put("nativeInsufficientBalanceRejected", true);
            report.put("guiRestoreSameAddress", true);
            report.put("ownedNodesStopped", true);
            report.put("genesis", info.get("genesisBlockId"));
            report.put("fullHeight", info.get("fullHeight"));
            report.put("walletHeight", status.get("walletHeight"));
            Files.writeString(output.resolve("gui-verification.json"), Json.stringify(report) + "\n", StandardCharsets.UTF_8);
            System.out.println("GUI create, protected seed backup, receive, lock/unlock, native validation and recovery passed.");
        } finally {
            if (ephemeralPhrase != null) Arrays.fill(ephemeralPhrase, '\0');
            if (createManager != null) createManager.close();
            if (restoreManager != null) restoreManager.close();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) window.dispose();
            });
        }
    }

    private static Path freshHome(Path root, String name) throws IOException {
        Path home = root.resolve(name + " wallet \u03a9");
        if (Files.exists(home)) throw new IOException("GUI tests require fresh isolated data directories; existing data is never reset");
        return home;
    }

    private static void copyImage(Path source, Path target) throws IOException {
        if (Files.exists(target)) throw new IOException("The GUI check must not replace an existing application directory");
        try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
            for (Path original : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(original)) throw new IOException("Windows packaging checks require regular bundled files");
                Path destination = target.resolve(source.relativize(original));
                if (Files.isDirectory(original)) Files.createDirectory(destination);
                else if (Files.isRegularFile(original)) Files.copy(original, destination);
                else throw new IOException("Unexpected application image entry");
            }
        }
    }

    private static void open(NodeManager manager) throws Exception {
        edt(() -> {
            Ui.install();
            current = new WalletWindow(manager);
            current.setVisible(true);
            current.start();
            return null;
        });
    }

    private static void closeWindow() throws Exception {
        await(() -> enabled("unlock-wallet"), "completed wallet requests before shutdown");
        SwingUtilities.invokeLater(() -> current.dispatchEvent(new WindowEvent(current, WindowEvent.WINDOW_CLOSING)));
        await(() -> !edtUnchecked(() -> current.isDisplayable()), "graceful desktop shutdown");
    }

    private static void selectTab(int index) throws Exception {
        edt(() -> { ((JTabbedPane) component("wallet-tabs")).setSelectedIndex(index); return null; });
    }

    private static void capture(Path output) throws Exception {
        Thread.sleep(400);
        Rectangle bounds = edt(() -> current.getBounds());
        BufferedImage image = new Robot().createScreenCapture(bounds);
        ImageIO.write(image, "png", output.toFile());
    }

    private static int addressCount() {
        return edtUnchecked(() -> ((JComboBox<?>) component("receive-addresses")).getItemCount());
    }

    private static void click(String name) {
        SwingUtilities.invokeLater(() -> ((AbstractButton) component(name)).doClick(0));
    }

    private static void text(String name, String value) throws Exception {
        edt(() -> { ((JTextComponent) component(name)).setText(value); return null; });
    }

    private static String readText(String name) {
        return edtUnchecked(() -> ((JTextComponent) component(name)).getText());
    }

    private static String readLabel(String name) {
        return edtUnchecked(() -> ((JLabel) component(name)).getText());
    }

    private static boolean enabled(String name) {
        return edtUnchecked(() -> {
            Component found = find(name);
            return found != null && found.isEnabled() && found.isShowing();
        });
    }

    private static boolean componentExists(String name) {
        return edtUnchecked(() -> find(name) != null && find(name).isShowing());
    }

    private static Component component(String name) {
        Component found = find(name);
        if (found == null) throw new AssertionError("Missing GUI component: " + name);
        return found;
    }

    private static Component find(String name) {
        for (Window window : Window.getWindows()) {
            if (!window.isDisplayable()) continue;
            Component found = findIn(window, name);
            if (found != null) return found;
        }
        return null;
    }

    private static Component findIn(Component component, String name) {
        if (name.equals(component.getName())) return component;
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                Component found = findIn(child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean dialogVisible(String title) {
        return edtUnchecked(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && ((JDialog) window).getTitle().equals(title) && window.isVisible()) return true;
            }
            return false;
        });
    }

    private static void closeMessage(String title) {
        SwingUtilities.invokeLater(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && ((JDialog) window).getTitle().equals(title)) window.dispose();
            }
        });
    }

    private static void await(BooleanSupplier condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private static <T> T edt(Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return operation.call();
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { value.set(operation.call()); }
            catch (Exception failure) { error.set(failure); }
        });
        if (error.get() != null) throw error.get();
        return value.get();
    }

    private static <T> T edtUnchecked(Callable<T> operation) {
        try { return edt(operation); }
        catch (Exception failure) { throw new AssertionError("Unable to inspect GUI state", failure); }
    }

    private static void reject(Runnable operation, String message) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
