package org.zyrexchain.desktop;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** An exact integer transfer form; the native wallet validates and signs the transaction. */
final class SendPanel extends JPanel {
    interface Host {
        void confirmSend(String address, long amount, long fee, Consumer<String> success);
    }

    private final Host host;
    private final JTextField recipient = new JTextField(44);
    private final JTextField amount = new JTextField(20);
    private final JTextField fee = new JTextField("0.001", 20);
    private final JLabel available = Ui.muted("Available balance: —");
    private final JLabel notice = Ui.muted("Unlock your wallet to send ZYRX.");
    private final JLabel resultLabel = Ui.muted(" ");
    private final JTextField transaction = new JTextField();
    private final JButton review = Ui.button("Review transaction", "send-review", true);
    private final JButton copyTransaction = Ui.button("Copy transaction ID", "copy-transaction", false);
    private long balance;
    private boolean ready;

    SendPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);
        JPanel form = Ui.card();
        form.setLayout(new GridBagLayout());
        put(form, Ui.title("Send ZYRX"), 0);
        put(form, Ui.paragraph("Send testnet coins from your wallet. Review the recipient, amount and fee before confirming."), 1);
        recipient.setName("send-recipient");
        amount.setName("send-amount");
        fee.setName("send-fee");
        recipient.setBorder(Ui.inputBorder());
        amount.setBorder(Ui.inputBorder());
        fee.setBorder(Ui.inputBorder());
        put(form, field("Recipient address", recipient), 2);
        put(form, field("Amount · ZYRX", amount), 3);
        put(form, available, 4);
        put(form, field("Network fee · ZYRX", fee), 5);
        put(form, notice, 6);
        review.addActionListener(event -> reviewTransfer());
        review.setEnabled(false);
        put(form, review, 7);
        resultLabel.setName("send-result");
        put(form, resultLabel, 8);
        transaction.setName("sent-transaction");
        transaction.setEditable(false);
        transaction.setBorder(Ui.inputBorder());
        transaction.setVisible(false);
        put(form, transaction, 9);
        copyTransaction.setVisible(false);
        copyTransaction.addActionListener(event -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(transaction.getText()), null);
                resultLabel.setText("Transaction ID copied.");
            } catch (IllegalStateException failure) {
                resultLabel.setText("The clipboard is unavailable. Select the transaction ID to copy it manually.");
            }
        });
        put(form, copyTransaction, 10);
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridy = 99;
        filler.weighty = 1;
        form.add(new JLabel(), filler);
        add(form, BorderLayout.CENTER);
    }

    void setReady(boolean ready, String reason, long confirmedBalance) {
        this.ready = ready;
        balance = confirmedBalance;
        available.setText("Confirmed balance: " + Units.format(balance) + " ZYRX");
        notice.setText(ready ? "Network fee is paid in addition to the amount." : reason);
        notice.setForeground(ready ? Ui.MUTED : Ui.WARNING);
        review.setEnabled(ready && balance > 0);
        if (ready && balance == 0) notice.setText("Receive testnet ZYRX before sending a transaction.");
    }

    private void reviewTransfer() {
        if (!ready) return;
        try {
            String address = recipient.getText().strip();
            NodeApi.validateAddress(address);
            long value = Units.parse(amount.getText().strip());
            long feeValue = Units.parse(fee.getText().strip());
            if (value <= 0) throw new IllegalArgumentException("Enter an amount greater than zero.");
            if (feeValue < 1_000_000L) throw new IllegalArgumentException("The network fee must be at least 0.001 ZYRX.");
            long total = Math.addExact(value, feeValue);
            if (total > balance) throw new IllegalArgumentException("The amount plus network fee exceeds your confirmed balance.");
            resultLabel.setText(" ");
            host.confirmSend(address, value, feeValue, id -> {
                recipient.setText("");
                amount.setText("");
                transaction.setText(id);
                transaction.setVisible(true);
                copyTransaction.setVisible(true);
                resultLabel.setForeground(Ui.ACCENT);
                resultLabel.setText("Submitted to the node. Waiting for a block confirmation.");
                revalidate();
            });
        } catch (ArithmeticException failure) {
            showError("The amount plus fee is too large.");
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
        }
    }

    void showError(String message) {
        resultLabel.setForeground(Ui.WARNING);
        resultLabel.setText(message);
    }

    private static JPanel field(String labelText, Component input) {
        JPanel field = new JPanel(new BorderLayout(0, 7));
        field.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setLabelFor(input);
        field.add(label, BorderLayout.NORTH);
        field.add(input, BorderLayout.CENTER);
        return field;
    }

    private static void put(JPanel panel, Component component, int row) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = Ui.insets(row == 0 ? 0 : 10, 0, 0, 0);
        panel.add(component, constraints);
    }
}
