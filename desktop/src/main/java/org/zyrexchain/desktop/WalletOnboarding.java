package org.zyrexchain.desktop;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

/** Wallet creation and recovery share the native encrypted wallet implementation. */
final class WalletOnboarding extends JPanel {
    interface Host {
        void create(Consumer<String> showPhrase);
        void restore(char[] phrase, char[] password);
        void backupCompleted(char[] phrase, char[] password);
    }

    private final Host host;
    private final CardLayout pages = new CardLayout();
    private final JPanel content = new JPanel(pages);
    private final JLabel error = new JLabel(" ");
    private final JPasswordField createPassword = password("create-password");
    private final JPasswordField createConfirmation = password("create-confirmation");
    private final JPasswordField restorePassword = password("restore-password");
    private final JPasswordField restoreConfirmation = password("restore-confirmation");
    private final JTextArea restorePhrase = new JTextArea(4, 35);
    private final JTextArea phraseDisplay = new JTextArea(8, 44);
    private final JTextField[] challenge = new JTextField[] {new JTextField(), new JTextField(), new JTextField()};
    private final JLabel[] challengeLabels = new JLabel[] {new JLabel(), new JLabel(), new JLabel()};
    private final JCheckBox saved = new JCheckBox("I have saved my recovery phrase in a safe place.");
    private char[] recoveryPhrase;
    private char[] creationPassword;
    private int[] challengePositions;

    WalletOnboarding(Host host) {
        super(new BorderLayout());
        this.host = host;
        setBorder(BorderFactory.createEmptyBorder(28, 38, 28, 38));
        content.setOpaque(false);
        content.add(welcome(), "welcome");
        content.add(createPage(), "create");
        content.add(restorePage(), "restore");
        content.add(backupPage(), "backup");
        error.setForeground(Ui.WARNING);
        error.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        error.setName("onboarding-error");
        add(content, BorderLayout.CENTER);
        add(error, BorderLayout.SOUTH);
        pages.show(content, "welcome");
    }

    private JPanel welcome() {
        JPanel page = form("Your wallet. Your node.",
            "Create a wallet or restore a recovery phrase. Zyrex starts and synchronizes your own node automatically.");
        put(page, Ui.paragraph("This application connects to the Zyrex public testnet. "
            + "Testnet coins are for testing and have no monetary value."), 2);
        JButton create = Ui.button("Create a wallet", "create-wallet", true);
        JButton restore = Ui.button("Restore a wallet", "restore-wallet", false);
        create.addActionListener(event -> navigate("create"));
        restore.addActionListener(event -> navigate("restore"));
        JPanel choices = new JPanel(new GridLayout(1, 2, 16, 0));
        choices.setOpaque(false);
        choices.add(create);
        choices.add(restore);
        put(page, choices, 3);
        put(page, Ui.paragraph("Your encrypted wallet stays on this computer. "
            + "Keep the recovery phrase offline: it is the only way to recover a lost wallet."), 4);
        return page;
    }

    private JPanel createPage() {
        JPanel page = form("Create your wallet", "Choose a password to encrypt the wallet on this computer.");
        put(page, field("Password · at least 12 characters", createPassword), 2);
        put(page, field("Confirm password", createConfirmation), 3);
        put(page, Ui.paragraph("Next, write down your recovery phrase. "
            + "Zyrex will ask you to confirm three words before opening your wallet."), 4);
        JButton submit = Ui.button("Create wallet", "create-submit", true);
        submit.addActionListener(event -> {
            char[] pass = validatedPassword(createPassword, createConfirmation);
            if (pass == null) return;
            eraseRecoveryPhrase();
            creationPassword = pass;
            clearPasswords();
            host.create(this::showBackup);
        });
        put(page, actions(submit), 5);
        return page;
    }

    private JPanel restorePage() {
        JPanel page = form("Restore your wallet", "Enter your existing recovery phrase in its original word order.");
        restorePhrase.setName("restore-phrase");
        restorePhrase.setLineWrap(true);
        restorePhrase.setWrapStyleWord(true);
        restorePhrase.setFont(Ui.BODY);
        restorePhrase.setBorder(Ui.inputBorder());
        put(page, field("Recovery phrase", new JScrollPane(restorePhrase)), 2);
        put(page, field("New password · at least 12 characters", restorePassword), 3);
        put(page, field("Confirm new password", restoreConfirmation), 4);
        JButton submit = Ui.button("Restore wallet", "restore-submit", true);
        submit.addActionListener(event -> {
            char[] pass = validatedPassword(restorePassword, restoreConfirmation);
            if (pass == null) return;
            String phrase = restorePhrase.getText().strip().replaceAll("\\s+", " ");
            int count = phrase.isEmpty() ? 0 : phrase.split(" ").length;
            if (count != 12 && count != 15 && count != 18 && count != 21 && count != 24) {
                Arrays.fill(pass, '\0');
                error.setText("Enter a complete 12, 15, 18, 21 or 24 word recovery phrase.");
                return;
            }
            char[] words = phrase.toCharArray();
            clearPasswords();
            restorePhrase.setText("");
            host.restore(words, pass);
        });
        put(page, actions(submit), 5);
        put(page, Ui.paragraph("Your wallet will scan the chain from the first block. "
            + "Keep the app open until the wallet scan catches up."), 6);
        return page;
    }

    private JPanel backupPage() {
        JPanel page = form("Save your recovery phrase", "Write these words down in order and keep them offline.");
        phraseDisplay.setEditable(false);
        phraseDisplay.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 15));
        phraseDisplay.setBorder(Ui.inputBorder());
        phraseDisplay.setName("recovery-phrase");
        phraseDisplay.getAccessibleContext().setAccessibleName("Recovery phrase. Keep these words private.");
        put(page, new JScrollPane(phraseDisplay), 2);
        put(page, Ui.paragraph("Anyone with these words can spend your coins. "
            + "They are shown here once and are never saved as plain text by this application."), 3);
        JPanel confirmation = new JPanel(new GridLayout(1, 3, 16, 0));
        confirmation.setOpaque(false);
        for (int index = 0; index < challenge.length; index++) {
            challenge[index].setName("backup-word-" + index);
            challenge[index].setBorder(Ui.inputBorder());
            confirmation.add(field(challengeLabels[index], challenge[index]));
        }
        put(page, confirmation, 4);
        saved.setName("backup-saved");
        saved.setOpaque(false);
        put(page, saved, 5);
        JButton done = Ui.button("Open my wallet", "backup-confirm", true);
        done.addActionListener(event -> verifyBackup());
        put(page, done, 6);
        return page;
    }

    private void showBackup(String phrase) {
        if (recoveryPhrase != null) Arrays.fill(recoveryPhrase, '\0');
        recoveryPhrase = phrase.toCharArray();
        String[] words = phrase.strip().split("\\s+");
        challengePositions = new int[] {0, words.length / 2, words.length - 1};
        StringBuilder display = new StringBuilder();
        for (int index = 0; index < words.length; index++) {
            display.append(String.format("%2d. %-14s", index + 1, words[index]));
            display.append(index % 3 == 2 ? '\n' : ' ');
        }
        phraseDisplay.setText(display.toString().stripTrailing());
        phraseDisplay.setCaretPosition(0);
        for (int index = 0; index < challenge.length; index++) {
            challengeLabels[index].setText("Word " + (challengePositions[index] + 1));
            challenge[index].setText("");
        }
        saved.setSelected(false);
        navigate("backup");
    }

    private void verifyBackup() {
        if (recoveryPhrase == null || challengePositions == null) return;
        String[] words = new String(recoveryPhrase).strip().split("\\s+");
        for (int index = 0; index < challenge.length; index++) {
            if (!words[challengePositions[index]].equals(challenge[index].getText().strip().toLowerCase(java.util.Locale.ROOT))) {
                error.setText("Check the requested words against your saved recovery phrase.");
                return;
            }
        }
        if (!saved.isSelected()) {
            error.setText("Confirm that you have saved the recovery phrase before continuing.");
            return;
        }
        char[] phrase = recoveryPhrase.clone();
        char[] password = creationPassword;
        creationPassword = null;
        eraseRecoveryPhrase();
        host.backupCompleted(phrase, password);
    }

    boolean hasRecoveryPhrase() {
        return recoveryPhrase != null;
    }

    void eraseRecoveryPhrase() {
        if (recoveryPhrase != null) Arrays.fill(recoveryPhrase, '\0');
        if (creationPassword != null) Arrays.fill(creationPassword, '\0');
        recoveryPhrase = null;
        creationPassword = null;
        phraseDisplay.setText("");
        for (JTextField word : challenge) word.setText("");
    }

    void setBusy(boolean busy) {
        setEnabledRecursively(content, !busy);
    }

    void showError(String message) {
        error.setText(message);
    }

    private void navigate(String page) {
        error.setText(" ");
        pages.show(content, page);
    }

    private void clearPasswords() {
        createPassword.setText("");
        createConfirmation.setText("");
        restorePassword.setText("");
        restoreConfirmation.setText("");
    }

    private char[] validatedPassword(JPasswordField field, JPasswordField confirmation) {
        char[] pass = field.getPassword();
        char[] check = confirmation.getPassword();
        boolean valid = pass.length >= 12 && pass.length <= 512 && Arrays.equals(pass, check);
        Arrays.fill(check, '\0');
        if (!valid) {
            Arrays.fill(pass, '\0');
            error.setText("Use a password of 12–512 characters and enter the same password twice.");
            return null;
        }
        return pass;
    }

    private JPanel actions(JButton submit) {
        JPanel actions = new JPanel(new GridLayout(1, 2, 16, 0));
        actions.setOpaque(false);
        JButton back = Ui.button("Back", "onboarding-back", false);
        back.addActionListener(event -> {
            eraseRecoveryPhrase();
            clearPasswords();
            restorePhrase.setText("");
            navigate("welcome");
        });
        actions.add(back);
        actions.add(submit);
        return actions;
    }

    private static JPasswordField password(String name) {
        return Ui.passwordField(name, 24);
    }

    private static JPanel field(String label, Component input) {
        return field(new JLabel(label), input);
    }

    private static JPanel field(JLabel label, Component input) {
        JPanel panel = new JPanel(new BorderLayout(0, 7));
        panel.setOpaque(false);
        label.setLabelFor(input);
        panel.add(label, BorderLayout.NORTH);
        panel.add(input, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel form(String title, String description) {
        JPanel panel = Ui.card();
        panel.setLayout(new GridBagLayout());
        put(panel, Ui.title(title), 0);
        put(panel, Ui.paragraph(description), 1);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = 99;
        filler.weighty = 1;
        panel.add(new JLabel(), filler);
        return panel;
    }

    private static void put(JPanel panel, Component component, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = Ui.insets(row == 0 ? 0 : 12, 0, 0, 0);
        panel.add(component, constraints);
    }

    private static void setEnabledRecursively(Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container) {
            for (Component child : ((java.awt.Container) component).getComponents()) setEnabledRecursively(child, enabled);
        }
    }
}
