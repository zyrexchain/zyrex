package org.zyrexchain.desktop;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;

/** All network and node operations run outside Swing's event dispatch thread. */
public final class WalletWindow extends JFrame implements WalletOnboarding.Host, SendPanel.Host {
    private final NodeManager manager;
    private volatile NodeApi api;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zyrex-desktop-node");
        thread.setDaemon(true);
        return thread;
    });
    private final CardLayout views = new CardLayout();
    private final JPanel content = new JPanel(views);
    private final JLabel networkStatus = new JLabel("Starting node");
    private final JLabel notice = new JLabel("Starting your node automatically…");
    private final JProgressBar activity = new JProgressBar();
    private final WalletOnboarding onboarding;
    private final SendPanel send;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel confirmed = new JLabel("—");
    private final JLabel pending = Ui.muted("—");
    private final JLabel walletState = Ui.muted("Wallet is locked");
    private final JLabel overviewSync = Ui.muted("Connecting to the testnet…");
    private final JLabel startupText = Ui.muted("Preparing your local full node.");
    private final JButton retry = Ui.button("Retry node startup", "retry-node", true);
    private final JButton walletToggle = Ui.button("Unlock wallet", "unlock-wallet", false);
    private final JButton generate = Ui.button("New address", "receive-new", true);
    private final JButton copyAddress = Ui.button("Copy address", "receive-copy", false);
    private final JComboBox<String> addresses = new JComboBox<>();
    private final JTextField receiveAddress = new JTextField();
    private final JLabel receiveNote = Ui.muted(" ");
    private final JLabel blockHeight = Ui.muted("—");
    private final JLabel headerHeight = Ui.muted("—");
    private final JLabel peerHeight = Ui.muted("—");
    private final JLabel walletHeight = Ui.muted("—");
    private final JLabel peerCount = Ui.muted("—");
    private final JLabel nodeState = Ui.muted("Starting");
    private final JLabel genesis = Ui.muted("—");
    private final JProgressBar chainProgress = new JProgressBar();
    private final DefaultTableModel historyRows = new DefaultTableModel(
        new Object[] {"Transaction", "Included block", "Confirmations", "Wallet outputs · ZYRX"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable historyTable = new JTable(historyRows);
    private final JLabel historyNote = Ui.muted("No wallet transactions yet.");
    private volatile boolean closing;
    private volatile boolean busy;
    private boolean connected;
    private Snapshot snapshot;

    public WalletWindow(NodeManager manager) {
        super("Zyrex · Wallet & Node · Testnet");
        this.manager = manager;
        onboarding = new WalletOnboarding(this);
        send = new SendPanel(this);
        setName("zyrex-desktop");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 660));
        setSize(1060, 810);
        setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Ui.BACKGROUND);
        root.add(header(), BorderLayout.NORTH);
        content.setOpaque(false);
        content.add(startup(), "startup");
        content.add(new JScrollPane(onboarding), "onboarding");
        tabs.setName("wallet-tabs");
        tabs.setBorder(BorderFactory.createEmptyBorder(18, 26, 18, 26));
        tabs.addTab("Overview", overview());
        tabs.addTab("Receive", receive());
        tabs.addTab("Send", wrap(send));
        tabs.addTab("History", history());
        tabs.addTab("Node", node());
        content.add(tabs, "wallet");
        root.add(content, BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);
        setContentPane(root);
        views.show(content, "startup");
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeApplication(); }
        });
    }

    public void start() {
        busy = true;
        setActivity(true);
        worker.execute(() -> {
            try {
                manager.start();
                api = manager.api();
                manager.awaitReady(Duration.ofSeconds(120));
                SwingUtilities.invokeLater(() -> busy = false);
                refresh();
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> startupFailed(failure));
            }
        });
        worker.scheduleWithFixedDelay(this::refresh, 3, 4, TimeUnit.SECONDS);
    }

    private JPanel header() {
        JPanel panel = new JPanel(new BorderLayout(20, 0));
        panel.setBackground(Ui.DARK);
        panel.setBorder(BorderFactory.createEmptyBorder(19, 28, 19, 28));
        JLabel brand = new JLabel("ZYREX");
        brand.setFont(Ui.BODY.deriveFont(Font.BOLD, 26));
        brand.setForeground(Color.WHITE);
        URL icon = WalletWindow.class.getResource("/zyrex-icon.png");
        if (icon != null) {
            Image image = new ImageIcon(icon).getImage();
            brand.setIcon(new ImageIcon(image.getScaledInstance(46, 46, Image.SCALE_SMOOTH)));
            brand.setIconTextGap(13);
            setIconImage(image);
        }
        JPanel identity = new JPanel(new BorderLayout(0, 1));
        identity.setOpaque(false);
        identity.add(brand, BorderLayout.CENTER);
        JLabel description = new JLabel("Wallet & Node");
        description.setForeground(new Color(0xa9bac6));
        identity.add(description, BorderLayout.SOUTH);
        panel.add(identity, BorderLayout.WEST);
        JPanel state = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 10));
        state.setOpaque(false);
        networkStatus.setForeground(new Color(0xa9bac6));
        networkStatus.setName("network-status");
        state.add(networkStatus);
        JLabel testnet = new JLabel("PUBLIC TESTNET");
        testnet.setFont(Ui.BODY.deriveFont(Font.BOLD, 12));
        testnet.setForeground(Ui.MINT);
        testnet.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x2b5848)),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        state.add(testnet);
        panel.add(state, BorderLayout.EAST);
        return panel;
    }

    private JPanel startup() {
        JPanel panel = new JPanel(new GridBagLayout());
        JPanel card = Ui.card();
        card.setLayout(new GridLayout(0, 1, 0, 18));
        card.add(Ui.title("Welcome to Zyrex"));
        card.add(Ui.paragraph("Your wallet and node run together in this app. "
            + "The first start downloads and verifies the public testnet."));
        card.add(startupText);
        retry.setVisible(false);
        retry.addActionListener(event -> restartNode());
        card.add(retry);
        card.setPreferredSize(new Dimension(620, 250));
        panel.add(card);
        return panel;
    }

    private JPanel footer() {
        JPanel footer = new JPanel(new BorderLayout(14, 0));
        footer.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.LINE),
            BorderFactory.createEmptyBorder(12, 28, 12, 28)));
        notice.setForeground(Ui.MUTED);
        notice.setName("application-notice");
        activity.setBorderPainted(false);
        activity.setPreferredSize(new Dimension(90, 7));
        footer.add(activity, BorderLayout.WEST);
        footer.add(notice, BorderLayout.CENTER);
        walletToggle.setVisible(false);
        walletToggle.addActionListener(event -> {
            if (snapshot != null && snapshot.unlocked()) {
                action("Locking your wallet…", () -> { api.lock(); return null; }, value -> { });
            } else showUnlock();
        });
        footer.add(walletToggle, BorderLayout.EAST);
        return footer;
    }

    private JPanel overview() {
        JPanel contentPanel = new JPanel(new BorderLayout(0, 18));
        contentPanel.setOpaque(false);
        JPanel balance = Ui.card();
        balance.setLayout(new BorderLayout(0, 16));
        balance.add(Ui.muted("Confirmed balance"), BorderLayout.NORTH);
        confirmed.setName("confirmed-balance");
        confirmed.setFont(Ui.BODY.deriveFont(Font.BOLD, 38));
        confirmed.setForeground(Ui.TEXT);
        balance.add(confirmed, BorderLayout.CENTER);
        balance.add(Ui.paragraph("ZYRX · Testnet coins are for testing and have no monetary value."), BorderLayout.SOUTH);
        contentPanel.add(balance, BorderLayout.NORTH);
        JPanel lower = new JPanel(new GridLayout(0, 1, 0, 18));
        lower.setOpaque(false);
        JPanel metrics = new JPanel(new GridLayout(1, 2, 18, 0));
        metrics.setOpaque(false);
        JPanel pendingCard = Ui.card();
        pendingCard.setLayout(new BorderLayout(0, 12));
        pendingCard.add(Ui.muted("Pending change"), BorderLayout.NORTH);
        pending.setName("pending-balance");
        pending.setFont(Ui.BODY.deriveFont(Font.BOLD, 23));
        pendingCard.add(pending, BorderLayout.CENTER);
        pendingCard.add(Ui.paragraph("Net balance change from unconfirmed transactions."), BorderLayout.SOUTH);
        metrics.add(pendingCard);
        JPanel walletCard = Ui.card();
        walletCard.setLayout(new BorderLayout(0, 12));
        walletCard.add(Ui.muted("Wallet protection"), BorderLayout.NORTH);
        walletState.setName("wallet-state");
        walletState.setFont(Ui.BODY.deriveFont(Font.BOLD, 23));
        walletCard.add(walletState, BorderLayout.CENTER);
        walletCard.add(Ui.paragraph("Your wallet is encrypted locally. Lock it when you finish using it."), BorderLayout.SOUTH);
        metrics.add(walletCard);
        lower.add(metrics);
        JPanel syncCard = Ui.card();
        syncCard.setLayout(new BorderLayout(0, 12));
        syncCard.add(Ui.title("Your node"), BorderLayout.NORTH);
        overviewSync.setName("overview-sync");
        syncCard.add(overviewSync, BorderLayout.CENTER);
        chainProgress.setStringPainted(true);
        syncCard.add(chainProgress, BorderLayout.SOUTH);
        lower.add(syncCard);
        contentPanel.add(lower, BorderLayout.CENTER);
        return wrap(contentPanel);
    }

    private JPanel receive() {
        JPanel panel = Ui.card();
        panel.setLayout(new GridBagLayout());
        row(panel, Ui.title("Receive ZYRX"), 0);
        row(panel, Ui.paragraph("Share a testnet address to receive coins. "
            + "Your existing addresses remain valid when you create another address."), 1);
        row(panel, new JLabel("Your addresses"), 2);
        addresses.setName("receive-addresses");
        addresses.addActionListener(event -> {
            Object selected = addresses.getSelectedItem();
            receiveAddress.setText(selected == null ? "" : selected.toString());
        });
        row(panel, addresses, 3);
        receiveAddress.setName("receive-address");
        receiveAddress.setEditable(false);
        receiveAddress.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        receiveAddress.setBorder(Ui.inputBorder());
        row(panel, receiveAddress, 4);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        copyAddress.addActionListener(event -> copyPublicText(receiveAddress.getText(), receiveNote));
        generate.addActionListener(event -> action("Creating a receiving address…", api::address, address -> {
            if (!containsAddress(address)) addresses.addItem(address);
            addresses.setSelectedItem(address);
            receiveNote.setText("New address created. Your earlier addresses still work.");
        }));
        actions.add(copyAddress);
        actions.add(javax.swing.Box.createHorizontalStrut(14));
        actions.add(generate);
        row(panel, actions, 5);
        receiveNote.setName("receive-notice");
        row(panel, receiveNote, 6);
        row(panel, Ui.paragraph("Use only Zyrex testnet addresses for this wallet. "
            + "A recovery phrase or wallet password is never needed to receive coins."), 7);
        fill(panel);
        return wrap(panel);
    }

    private JPanel history() {
        JPanel panel = Ui.card();
        panel.setLayout(new BorderLayout(0, 14));
        panel.add(Ui.title("Transactions"), BorderLayout.NORTH);
        historyTable.setName("wallet-history");
        historyTable.setRowHeight(35);
        historyTable.setShowVerticalLines(false);
        historyTable.setFillsViewportHeight(true);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(460);
        historyTable.setAutoCreateRowSorter(true);
        panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new GridLayout(0, 1, 0, 9));
        bottom.setOpaque(false);
        historyNote.setName("history-status");
        bottom.add(historyNote);
        bottom.add(Ui.paragraph("Wallet outputs show outputs to your addresses, including change. "
            + "They are not net transfer amounts. Select a transaction to copy its full ID."));
        JButton copy = Ui.button("Copy selected transaction ID", "history-copy", false);
        copy.addActionListener(event -> {
            int selected = historyTable.getSelectedRow();
            if (selected >= 0) {
                int modelRow = historyTable.convertRowIndexToModel(selected);
                copyPublicText(String.valueOf(historyRows.getValueAt(modelRow, 0)), historyNote);
            }
        });
        bottom.add(copy);
        panel.add(bottom, BorderLayout.SOUTH);
        return wrap(panel);
    }

    private JPanel node() {
        JPanel panel = Ui.card();
        panel.setLayout(new GridBagLayout());
        row(panel, Ui.title("Your full node"), 0);
        row(panel, Ui.paragraph("Zyrex verifies blocks on this computer. "
            + "The app starts the node automatically and stops it safely when you close the window."), 1);
        JPanel details = new JPanel(new GridLayout(0, 2, 12, 17));
        details.setOpaque(false);
        metric(details, "Node status", nodeState);
        metric(details, "Verified block height", blockHeight);
        metric(details, "Downloaded header height", headerHeight);
        metric(details, "Connected peers' maximum height", peerHeight);
        metric(details, "Wallet scan height", walletHeight);
        metric(details, "Connected peers", peerCount);
        metric(details, "Genesis block", genesis);
        row(panel, details, 2);
        JTextField folder = new JTextField(manager.dataHome().toString());
        folder.setName("wallet-data-folder");
        folder.setEditable(false);
        folder.setBorder(Ui.inputBorder());
        row(panel, new JLabel("Wallet and node data folder"), 3);
        row(panel, folder, 4);
        JButton open = Ui.button("Open data folder", "open-data-folder", false);
        open.addActionListener(event -> {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                showError("Open this folder with your file manager to view the node log and encrypted wallet files.");
                return;
            }
            action("Opening your data folder…", () -> { Desktop.getDesktop().open(manager.dataHome().toFile()); return null; }, value -> { });
        });
        row(panel, open, 5);
        row(panel, Ui.paragraph("Balances reflect the wallet's scan height. "
            + "Sending is enabled once the wallet and verified blocks catch up to connected peers."), 6);
        fill(panel);
        return wrap(panel);
    }

    private void refresh() {
        if (closing) return;
        if (!manager.isRunning()) {
            if (!busy) SwingUtilities.invokeLater(() -> connectionFailed(new java.io.IOException("Your node stopped.")));
            return;
        }
        try {
            Map<String, Object> info = api.info();
            Map<String, Object> status = api.status();
            boolean initialized = Boolean.TRUE.equals(status.get("isInitialized"));
            Snapshot next = initialized
                ? new Snapshot(info, status, api.balance(), api.balanceWithUnconfirmed(), api.addresses(), api.transactions())
                : new Snapshot(info, status, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());
            SwingUtilities.invokeLater(() -> apply(next));
        } catch (Exception failure) {
            SwingUtilities.invokeLater(() -> connectionFailed(failure));
        }
    }

    private void apply(Snapshot next) {
        if (closing) return;
        snapshot = next;
        connected = true;
        retry.setVisible(false);
        int full = number(next.info, "fullHeight");
        int headers = number(next.info, "headersHeight");
        int peers = number(next.info, "peersCount");
        int target = Math.max(headers, number(next.info, "maxPeerHeight"));
        boolean caughtUp = full > 0 && full >= target && peers > 0;
        networkStatus.setText(caughtUp ? "Connected · " + peers + " peer" + (peers == 1 ? "" : "s") : "Synchronizing");
        networkStatus.setForeground(caughtUp ? Ui.MINT : new Color(0xa9bac6));
        startupText.setText("Node is connected. Opening your wallet…");
        if (!onboarding.hasRecoveryPhrase()) {
            if (next.initialized()) {
                views.show(content, "wallet");
            } else views.show(content, "onboarding");
        }
        confirmed.setText(Units.format(next.balance()));
        long change = next.pendingBalance() - next.balance();
        pending.setText((change > 0 ? "+" : "") + Units.format(change) + " ZYRX");
        walletState.setText(next.unlocked() ? "Unlocked" : "Locked");
        walletState.setForeground(next.unlocked() ? Ui.ACCENT : Ui.TEXT);
        blockHeight.setText(Integer.toString(full));
        headerHeight.setText(Integer.toString(headers));
        peerHeight.setText(Integer.toString(number(next.info, "maxPeerHeight")));
        walletHeight.setText(Integer.toString(number(next.status, "walletHeight")));
        peerCount.setText(Integer.toString(peers));
        String genesisId = String.valueOf(next.info.getOrDefault("genesisBlockId", ""));
        genesis.setText(genesisId.length() > 20 ? genesisId.substring(0, 20) + "…" : genesisId);
        genesis.setToolTipText(genesisId);
        nodeState.setText(caughtUp ? "Caught up to connected peers" : peers == 0 ? "Looking for peers" : "Verifying blocks");
        overviewSync.setText("Block " + full + " · " + headers + " headers · " + peers + " connected peers · Wallet scan "
            + number(next.status, "walletHeight"));
        chainProgress.setIndeterminate(peers == 0 || target == 0);
        chainProgress.setMaximum(Math.max(1, target));
        chainProgress.setValue(full);
        chainProgress.setString(caughtUp ? "Blocks caught up to connected peers" : "Verifying " + full + " / " + target + " blocks");
        updateAddresses(next.addresses);
        updateHistory(next);
        updateControls();
        if (!busy) {
            setActivity(!caughtUp);
            notice.setForeground(Ui.MUTED);
            String walletError = string(next.status, "error");
            notice.setText(!walletError.isEmpty() ? "Wallet scan needs attention. Reopen the app to retry safely."
                : !caughtUp ? "Synchronizing with the testnet. You can create or restore a wallet while the node catches up."
                : number(next.status, "walletHeight") < full && next.initialized() ? "Scanning wallet history…"
                : next.unlocked() ? "Your node and wallet are ready." : "Node connected. Unlock your wallet to send coins.");
        }
    }

    private void updateControls() {
        boolean initialized = snapshot != null && snapshot.initialized();
        boolean unlocked = initialized && snapshot.unlocked();
        walletToggle.setVisible(initialized && !onboarding.hasRecoveryPhrase());
        walletToggle.setText(unlocked ? "Lock wallet" : "Unlock wallet");
        walletToggle.setEnabled(connected && !busy && !closing);
        generate.setEnabled(connected && unlocked && !busy && !closing);
        copyAddress.setEnabled(!receiveAddress.getText().isEmpty() && !closing);
        String reason = !connected ? "Waiting for your node to reconnect."
            : busy ? "Completing your previous request…" : !unlocked ? "Unlock your wallet to send ZYRX."
            : !snapshot.caughtUp() ? "Wait for the node and wallet scan to catch up to connected peers."
            : !string(snapshot.status, "error").isEmpty() ? "Wallet scan needs attention before sending." : "";
        send.setReady(reason.isEmpty() && !closing, reason, initialized ? snapshot.balance() : 0);
        onboarding.setBusy(busy || !connected || closing);
    }

    private void updateAddresses(List<String> next) {
        String selected = (String) addresses.getSelectedItem();
        List<String> existing = new ArrayList<>();
        for (int index = 0; index < addresses.getItemCount(); index++) existing.add(addresses.getItemAt(index));
        if (existing.equals(next)) return;
        addresses.removeAllItems();
        for (String address : next) addresses.addItem(address);
        if (selected != null && next.contains(selected)) addresses.setSelectedItem(selected);
    }

    private boolean containsAddress(String address) {
        for (int index = 0; index < addresses.getItemCount(); index++) if (address.equals(addresses.getItemAt(index))) return true;
        return false;
    }

    private void updateHistory(Snapshot next) {
        Set<String> own = new HashSet<>(next.addresses);
        historyRows.setRowCount(0);
        for (Map<String, Object> transaction : next.transactions) {
            long ownOutputs = 0;
            Object outputs = transaction.get("outputs");
            if (outputs instanceof List) {
                for (Object entry : (List<?>) outputs) {
                    if (entry instanceof Map) {
                        Map<?, ?> output = (Map<?, ?>) entry;
                        if (own.contains(output.get("address")) && output.get("value") instanceof Number) {
                            ownOutputs = Math.addExact(ownOutputs, ((Number) output.get("value")).longValue());
                        }
                    }
                }
            }
            int included = number(transaction, "inclusionHeight");
            historyRows.addRow(new Object[] {string(transaction, "id"), included >= 0 ? Integer.toString(included) : "Pending",
                number(transaction, "numConfirmations"), Units.format(ownOutputs)});
        }
        historyNote.setText(next.transactions.isEmpty() ? "No wallet transactions yet."
            : next.transactions.size() + " wallet transaction" + (next.transactions.size() == 1 ? "" : "s"));
    }

    @Override public void create(Consumer<String> showPhrase) {
        action("Preparing your recovery phrase…", NodeApi::createRecoveryPhrase, showPhrase);
    }

    @Override public void restore(char[] phrase, char[] password) {
        action("Restoring your wallet and scanning its history…", () -> {
            try { api.restore(new String(phrase), new String(password)); return null; }
            finally { Arrays.fill(phrase, '\0'); Arrays.fill(password, '\0'); }
        }, result -> notice.setText("Wallet restored. Scanning from the first block…"));
    }

    @Override public void backupCompleted(char[] phrase, char[] password) {
        action("Creating your encrypted wallet and scanning its history…", () -> {
            try { api.restore(new String(phrase), new String(password)); return null; }
            finally { Arrays.fill(phrase, '\0'); Arrays.fill(password, '\0'); }
        }, result -> {
            views.show(content, "wallet");
            notice.setText("Wallet created. Your recovery phrase has been confirmed.");
        });
    }

    private void showUnlock() {
        if (busy || !connected || closing) return;
        JDialog dialog = new JDialog(this, "Unlock your wallet", true);
        dialog.setName("unlock-dialog");
        JPanel panel = Ui.card();
        panel.setLayout(new BorderLayout(0, 16));
        JTextArea guidance = Ui.paragraph("Enter your wallet password.\nIt stays on this computer.");
        guidance.setRows(2);
        panel.add(guidance, BorderLayout.NORTH);
        JPasswordField password = Ui.passwordField("unlock-password", 26);
        panel.add(password, BorderLayout.CENTER);
        JButton unlock = Ui.button("Unlock", "unlock-submit", true);
        unlock.addActionListener(event -> {
            char[] pass = password.getPassword();
            password.setText("");
            if (pass.length == 0) { Arrays.fill(pass, '\0'); return; }
            dialog.dispose();
            action("Unlocking your wallet…", () -> {
                try { api.unlock(new String(pass)); return null; }
                finally { Arrays.fill(pass, '\0'); }
            }, result -> { });
        });
        password.addActionListener(event -> unlock.doClick());
        panel.add(unlock, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(unlock);
        dialog.pack();
        dialog.setMinimumSize(dialog.getSize());
        dialog.setLocationRelativeTo(this);
        dialog.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent event) { password.requestFocusInWindow(); }
            @Override public void windowClosed(WindowEvent event) { password.setText(""); }
        });
        dialog.setVisible(true);
    }

    @Override public void confirmSend(String address, long amount, long fee, Consumer<String> success) {
        if (busy || snapshot == null || !snapshot.caughtUp() || !snapshot.unlocked() || !connected) return;
        JPanel details = new JPanel(new GridLayout(0, 1, 0, 10));
        details.add(new JLabel("Send " + Units.format(amount) + " ZYRX on the public testnet"));
        JTextField recipient = new JTextField(address, 48);
        recipient.setEditable(false);
        recipient.setBorder(Ui.inputBorder());
        details.add(recipient);
        details.add(new JLabel("Network fee: " + Units.format(fee) + " ZYRX"));
        details.add(new JLabel("Total: " + Units.format(Math.addExact(amount, fee)) + " ZYRX"));
        details.add(Ui.muted("Check the complete recipient address before confirming."));
        int answer = JOptionPane.showOptionDialog(this, details, "Confirm transaction", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, new Object[] {"Send ZYRX", "Cancel"}, "Cancel");
        if (answer != 0) return;
        action("Signing and submitting your transaction…", () -> api.send(address, amount, fee), success);
    }

    private <T> void action(String description, Callable<T> work, Consumer<T> success) {
        if (busy || closing) return;
        busy = true;
        notice.setText(description);
        notice.setForeground(Ui.MUTED);
        setActivity(true);
        updateControls();
        worker.execute(() -> {
            try {
                T result = work.call();
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    if (closing) return;
                    success.accept(result);
                    updateControls();
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    showError(friendly(failure));
                    onboarding.showError(friendly(failure));
                    send.showError(friendly(failure));
                    updateControls();
                });
            }
            refresh();
        });
    }

    private void connectionFailed(Exception failure) {
        if (closing) return;
        connected = false;
        networkStatus.setText(manager.isRunning() ? "Reconnecting" : "Node stopped");
        networkStatus.setForeground(new Color(0xffbe79));
        notice.setText(manager.isRunning() ? "Waiting for your node to respond. Existing wallet data is preserved."
            : "Your node stopped. Retry startup to continue with the same wallet and chain data.");
        notice.setForeground(Ui.WARNING);
        nodeState.setText(manager.isRunning() ? "Reconnecting" : "Stopped");
        startupText.setText("Your wallet data is preserved. " + friendly(failure));
        retry.setVisible(!manager.isRunning());
        setActivity(manager.isRunning());
        if (!manager.isRunning()) views.show(content, "startup");
        updateControls();
    }

    private void startupFailed(Exception failure) {
        busy = false;
        connected = false;
        setActivity(false);
        startupText.setText(friendly(failure));
        retry.setVisible(true);
        retry.setEnabled(true);
        notice.setText("Node startup failed. Retry without deleting your wallet or chain data.");
        networkStatus.setText("Startup needs attention");
        updateControls();
    }

    private void restartNode() {
        if (busy || closing) return;
        busy = true;
        retry.setEnabled(false);
        setActivity(true);
        startupText.setText("Starting your node with the existing data…");
        worker.execute(() -> {
            try {
                manager.stop();
                manager.start();
                api = manager.api();
                manager.awaitReady(Duration.ofSeconds(120));
                SwingUtilities.invokeLater(() -> busy = false);
                refresh();
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> startupFailed(failure));
            }
        });
    }

    private void closeApplication() {
        if (closing) return;
        if (onboarding.hasRecoveryPhrase()) {
            int answer = JOptionPane.showOptionDialog(this,
                "Your wallet has not been created yet. Return to save and confirm the recovery phrase, "
                    + "or cancel setup and close the app.",
                "Save your recovery phrase", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                null, new Object[] {"Return to backup", "Cancel setup and close"}, "Return to backup");
            if (answer != 1) return;
            onboarding.eraseRecoveryPhrase();
        }
        if (busy) {
            JOptionPane.showMessageDialog(this, "Zyrex is completing your request. Please wait before closing the app.",
                "Request in progress", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        closing = true;
        onboarding.eraseRecoveryPhrase();
        notice.setText("Saving wallet and chain data. Stopping your node safely…");
        setActivity(true);
        updateControls();
        worker.execute(() -> {
            try {
                manager.close();
                worker.shutdown();
                SwingUtilities.invokeLater(this::dispose);
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    closing = false;
                    showError("Your node is still stopping. Wait and close the window again; your data remains preserved.");
                    updateControls();
                });
            }
        });
    }

    private void showError(String message) {
        notice.setForeground(Ui.WARNING);
        notice.setText(message);
        setActivity(false);
    }

    private void setActivity(boolean active) {
        activity.setIndeterminate(active);
        activity.setVisible(active);
    }

    private static String friendly(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? "The request could not be completed. Your wallet data is preserved." : message;
    }

    private static void copyPublicText(String text, JLabel feedback) {
        if (text.isEmpty()) return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            feedback.setText("Copied to clipboard.");
        } catch (IllegalStateException failure) {
            feedback.setText("The clipboard is unavailable. Select and copy the text manually.");
        }
    }

    private static JPanel wrap(Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        panel.add(component, BorderLayout.CENTER);
        return panel;
    }

    private static void metric(JPanel panel, String label, JLabel value) {
        panel.add(Ui.muted(label));
        panel.add(value);
    }

    private static void row(JPanel panel, Component component, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = Ui.insets(row == 0 ? 0 : 15, 0, 0, 0);
        panel.add(component, constraints);
    }

    private static void fill(JPanel panel) {
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = 99;
        filler.weighty = 1;
        panel.add(new JLabel(), filler);
    }

    private static int number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static String string(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private static final class Snapshot {
        final Map<String, Object> info;
        final Map<String, Object> status;
        final Map<String, Object> balance;
        final Map<String, Object> withUnconfirmed;
        final List<String> addresses;
        final List<Map<String, Object>> transactions;

        Snapshot(Map<String, Object> info, Map<String, Object> status, Map<String, Object> balance,
                Map<String, Object> withUnconfirmed, List<String> addresses, List<Map<String, Object>> transactions) {
            this.info = info;
            this.status = status;
            this.balance = balance;
            this.withUnconfirmed = withUnconfirmed;
            this.addresses = addresses;
            this.transactions = transactions;
        }

        boolean initialized() { return Boolean.TRUE.equals(status.get("isInitialized")); }
        boolean unlocked() { return Boolean.TRUE.equals(status.get("isUnlocked")); }
        long balance() { return balance.get("balance") instanceof Number ? ((Number) balance.get("balance")).longValue() : 0; }
        long pendingBalance() {
            return withUnconfirmed.get("balance") instanceof Number ? ((Number) withUnconfirmed.get("balance")).longValue() : balance();
        }
        boolean caughtUp() {
            int full = number(info, "fullHeight");
            return full > 0 && number(info, "peersCount") > 0 && full >= number(info, "headersHeight")
                && full >= number(info, "maxPeerHeight") && number(status, "walletHeight") >= full;
        }
    }
}
