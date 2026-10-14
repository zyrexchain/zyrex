package org.zyrexchain.desktop;

import java.awt.Component;
import java.awt.GridLayout;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import org.ergoplatform.ZyrexAddressEncoder;

/** One immutable signed transaction, quoted before confirmation and broadcast without signing again. */
final class PreparedTransfer {
    static final long PREVIEW_LIFETIME_MILLIS = 120_000L;
    final String address;
    final long amount;
    final long fee;
    final String id;
    final String transactionJson;
    final String feeBasis;
    final int size;
    private final long createdAt = System.nanoTime();

    PreparedTransfer(String address, long amount, long fee, Map<String, Object> transaction, String feeBasis, int size)
            throws IOException {
        this(address, amount, fee, transaction, feeBasis, size, Collections.emptyList());
    }

    PreparedTransfer(String address, long amount, long fee, Map<String, Object> transaction, String feeBasis, int size,
            List<String> walletAddresses) throws IOException {
        NodeApi.validateAddress(address);
        if (amount <= 0 || fee < Units.DEFAULT_FEE || amount > Long.MAX_VALUE - fee) {
            throw new IllegalArgumentException("Invalid transfer amount or fee");
        }
        Object identifier = transaction.get("id");
        if (!(identifier instanceof String) || !((String) identifier).matches("[0-9a-f]{64}")) {
            throw new IOException("The node returned an invalid signed transaction ID");
        }
        this.address = address;
        this.amount = amount;
        this.fee = fee;
        this.id = (String) identifier;
        this.transactionJson = Json.stringify(transaction);
        this.feeBasis = feeBasis;
        this.size = size;
        validateOutputs(transaction, walletAddresses);
    }

    private void validateOutputs(Map<String, Object> transaction, List<String> walletAddresses) throws IOException {
        try {
            String recipientTree = ZyrexAddressEncoder.apply((byte) 64).fromString(address).get().script().bytesHex();
            Set<String> owned = new HashSet<>();
            if (walletAddresses.size() > 5000) throw new IOException("The wallet has too many addresses for bounded transfer review");
            for (String own : walletAddresses) {
                NodeApi.validateAddress(own);
                owned.add(ZyrexAddressEncoder.apply((byte) 64).fromString(own).get().script().bytesHex());
            }
            int index = 0;
            long paidFee = 0;
            for (Object item : Json.asList(transaction.get("outputs"))) {
                Map<String, Object> output = Json.asObject(item);
                long value = Json.asLong(output.get("value"));
                if (value <= 0) throw new IllegalArgumentException("Invalid output amount");
                boolean bare = !output.containsKey("assets") || Json.asList(output.get("assets")).isEmpty();
                if (index++ == 0) {
                    if (!recipientTree.equals(output.get("ergoTree")) || value != amount || !bare) {
                        throw new IOException("The signed payment output does not match the reviewed recipient and amount");
                    }
                } else if (WalletHistory.feeTree().equals(output.get("ergoTree"))) {
                    if (!bare) throw new IOException("The network fee output contains unexpected assets");
                    paidFee = Math.addExact(paidFee, value);
                } else if (!owned.contains(output.get("ergoTree"))) {
                    throw new IOException("The signed transaction contains an additional external payment");
                }
            }
            if (index == 0 || paidFee != fee || Json.asList(transaction.get("inputs")).isEmpty()) {
                throw new IOException("The signed transaction does not match its recipient, amount and fee");
            }
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("The signed transaction contains invalid outputs", failure);
        }
    }

    Map<String, Object> transaction() { return Json.asObject(Json.parse(transactionJson)); }

    void requireFresh() throws IOException {
        if (System.nanoTime() - createdAt > PREVIEW_LIFETIME_MILLIS * 1_000_000L) {
            throw new IOException("The transaction preview expired. Review the transaction again before sending");
        }
    }

    boolean confirm(Component parent) {
        JPanel details = new JPanel(new GridLayout(0, 1, 0, 10));
        details.add(new JLabel("Send " + Units.format(amount) + " ZYRX on the public testnet"));
        JTextField recipient = new JTextField(address, 48);
        recipient.setEditable(false);
        recipient.setBorder(Ui.inputBorder());
        details.add(recipient);
        JLabel feeLabel = new JLabel("Network fee: " + Units.format(fee) + " ZYRX");
        feeLabel.setName("review-network-fee");
        details.add(feeLabel);
        JLabel total = new JLabel("Total: " + Units.format(Math.addExact(amount, fee)) + " ZYRX");
        total.setName("review-total");
        details.add(total);
        JLabel basis = Ui.muted(feeBasis + (size > 0 ? " · " + size + " signed bytes" : ""));
        basis.setName("review-fee-basis");
        details.add(basis);
        JTextField transactionId = new JTextField(id, 48);
        transactionId.setName("review-transaction-id");
        transactionId.setEditable(false);
        transactionId.setToolTipText("The signed transaction ID is preserved if submission needs a retry");
        details.add(transactionId);
        details.add(Ui.muted("Check the complete recipient address before confirming."));
        return JOptionPane.showOptionDialog(parent, details, "Confirm transaction", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE, null, new Object[] {"Send ZYRX", "Cancel"}, "Cancel") == 0;
    }
}
