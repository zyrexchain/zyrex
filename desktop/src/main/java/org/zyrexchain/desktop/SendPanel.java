package org.zyrexchain.desktop;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.text.View;

/** An exact integer transfer form; the native wallet validates and signs the transaction. */
final class SendPanel extends JPanel {
    interface Host {
        void confirmSend(String address, long amount, long fee, Consumer<String> success);
    }

    private final Host host;
    private final JTextField recipient = Ui.textField("send-recipient", 44);
    private final JTextField amount = Ui.textField("send-amount", 20);
    private final JTextField fee = Ui.textField("send-fee", 20);
    private final JLabel available = Ui.muted("Available balance: —");
    private final JTextArea notice = message("Unlock your wallet to send ZYRX.");
    private final JTextArea resultLabel = message(" ");
    private final JTextField transaction = Ui.textField("sent-transaction", 0);
    private final JButton review = Ui.button("Review transaction", "send-review", true);
    private final JButton copyTransaction = Ui.button("Copy transaction ID", "copy-transaction", false);
    private final Form content = new Form();
    private final JScrollPane scroll = new JScrollPane(content,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private long balance;
    private boolean ready;
    private boolean showingTransactionStatus;

    SendPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);
        JPanel form = content.card;
        form.setName("send-form");
        form.setLayout(new GridBagLayout());
        JLabel title = Ui.title("Send ZYRX");
        title.setName("send-title");
        put(form, title, 0);
        JTextArea introduction = message("Send testnet coins from your wallet. Review the recipient, amount and fee before confirming.");
        introduction.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        put(form, introduction, 1);
        fee.setText("0.001");
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
        transaction.setEditable(false);
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
        scroll.setName("send-scroll");
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        add(scroll, BorderLayout.CENTER);
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
            showingTransactionStatus = false;
            resultLabel.setText(" ");
            host.confirmSend(address, value, feeValue, id -> {
                recipient.setText("");
                amount.setText("");
                transaction.setText(id);
                transaction.setVisible(true);
                copyTransaction.setVisible(true);
                resultLabel.setForeground(Ui.ACCENT);
                resultLabel.setText("Submitted to the node. Waiting for a block confirmation.");
                showingTransactionStatus = true;
                showResult();
            });
        } catch (ArithmeticException failure) {
            showError("The amount plus fee is too large.");
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
        }
    }

    void showError(String message) {
        showingTransactionStatus = false;
        resultLabel.setForeground(Ui.WARNING);
        resultLabel.setText(message);
        showResult();
    }

    void updateTransactionStatus(String id, String status, boolean included) {
        if (!showingTransactionStatus || !id.equals(transaction.getText())) return;
        resultLabel.setForeground(included ? Ui.ACCENT : Ui.MUTED);
        resultLabel.setText(status);
        revalidate();
    }

    private void showResult() {
        revalidate();
        SwingUtilities.invokeLater(() -> {
            Rectangle target = SwingUtilities.convertRectangle(resultLabel.getParent(), resultLabel.getBounds(), content);
            if (copyTransaction.isVisible()) {
                target.add(SwingUtilities.convertRectangle(copyTransaction.getParent(), copyTransaction.getBounds(), content));
            }
            content.scrollRectToVisible(target);
        });
    }

    private static JTextArea message(String text) {
        JTextArea area = new WrappedMessage(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Ui.BODY);
        area.setForeground(Ui.MUTED);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
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

    /** Preserve complete rows while the viewport follows the available width. */
    private static final class Form extends JPanel implements Scrollable {
        private final JPanel card = Ui.card();

        private Form() {
            super(new BorderLayout());
            setOpaque(false);
            add(card, BorderLayout.NORTH);
        }

        @Override public Dimension getPreferredSize() {
            return new Dimension(viewWidth(), card.getPreferredSize().height);
        }

        private int viewWidth() {
            int width = getParent() instanceof JViewport ? getParent().getWidth() : getWidth();
            return width > 0 ? width : 640;
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(20, visible.height - 20);
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Measure wrapped rows at viewport width before GridBagLayout assigns their height. */
    private static final class WrappedMessage extends JTextArea {
        private WrappedMessage(String text) { super(text); }

        @Override public Dimension getPreferredSize() {
            Form form = (Form) SwingUtilities.getAncestorOfClass(Form.class, this);
            if (form == null || getUI() == null) return super.getPreferredSize();
            int width = Math.max(1, form.viewWidth() - form.card.getInsets().left - form.card.getInsets().right);
            Insets padding = getInsets();
            View view = getUI().getRootView(this);
            view.setSize(Math.max(1, width - padding.left - padding.right), Integer.MAX_VALUE);
            int height = (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS)) + padding.top + padding.bottom;
            return new Dimension(width, height);
        }

        @Override public Dimension getMinimumSize() { return new Dimension(1, getPreferredSize().height); }

        // Read-only status text must not move the viewport when its caret/document changes.
        @Override public void scrollRectToVisible(Rectangle bounds) { }
    }
}
