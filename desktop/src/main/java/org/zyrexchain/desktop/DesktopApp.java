package org.zyrexchain.desktop;

import java.nio.file.Path;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** A single desktop application owns both the wallet and its local full node. */
public final class DesktopApp {
    public static final String VERSION = "0.1.3-testnet";

    private DesktopApp() { }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("Zyrex Desktop " + VERSION);
            return;
        }
        try {
            Path appDirectory = Path.of(DesktopApp.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            Path nodeJar = Path.of(System.getProperty("zyrex.nodeJar", appDirectory.resolve("zyrex.jar").toString()));
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
            Path home = System.getProperty("zyrex.dataHome") == null
                ? NodeManager.defaultDataHome() : Path.of(System.getProperty("zyrex.dataHome"));
            NodeManager manager = new NodeManager(home, java, nodeJar);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { manager.close(); } catch (Exception ignored) { }
            }, "zyrex-desktop-shutdown"));
            SwingUtilities.invokeLater(() -> {
                Ui.install();
                WalletWindow window = new WalletWindow(manager);
                window.setVisible(true);
                window.start();
            });
        } catch (Exception failure) {
            String message = failure.getMessage() != null && failure.getMessage().contains("already using this data directory")
                ? "Zyrex is already running. Open the existing application window."
                : "Zyrex could not open its application files. Check that the complete application folder is present "
                    + "and that your wallet data folder is writable.";
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                message,
                "Unable to start Zyrex", JOptionPane.ERROR_MESSAGE));
        }
    }
}
