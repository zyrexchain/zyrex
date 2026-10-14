package org.zyrexchain.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

/** Owner-only recovery of one signed transaction whose submission may be uncertain. */
final class PendingTransferStore {
    private static final int FILE_LIMIT = 2 * 1024 * 1024;
    private final Path home;
    private final Path file;

    PendingTransferStore(Path home) {
        this.home = home.toAbsolutePath().normalize();
        file = this.home.resolve("prepared-transfer.json");
    }

    boolean exists() { return Files.exists(file, LinkOption.NOFOLLOW_LINKS); }

    void save(PreparedTransfer transfer, String wallet) throws IOException {
        if (exists()) throw new IOException("Check or retry the saved transaction before preparing another transfer");
        NodeApi.validateAddress(wallet);
        byte[] bytes = Json.stringify(Json.object("version", 1, "genesis", NodeApi.TESTNET_GENESIS, "dataHome", home.toString(),
            "wallet", wallet, "recipient", transfer.address, "amount", transfer.amount, "fee", transfer.fee,
            "transaction", transfer.transaction())).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FILE_LIMIT) throw new IOException("The signed transaction recovery record exceeds its size limit");
        NodeManager.writePrivate(file, bytes, false);
        try (FileChannel saved = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { saved.force(true); }
        forceDirectory();
    }

    PreparedTransfer load(List<String> addresses) throws IOException {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > FILE_LIMIT) {
                throw new IOException("The saved transaction must be a bounded regular private file");
            }
            NodeManager.requirePrivateFile(file, false);
            Map<String, Object> data = Json.asObject(Json.parse(Files.readString(file)));
            if (Json.asLong(data.get("version")) != 1 || !NodeApi.TESTNET_GENESIS.equals(data.get("genesis"))
                    || !home.toString().equals(data.get("dataHome")) || !addresses.contains(data.get("wallet"))) {
                throw new IOException("The saved transaction does not belong to this wallet and network");
            }
            return new PreparedTransfer((String) data.get("recipient"), Json.asLong(data.get("amount")), Json.asLong(data.get("fee")),
                Json.asObject(data.get("transaction")), "Saved signed transaction · fee unchanged", 0, addresses);
        } catch (IllegalArgumentException | ArithmeticException | ClassCastException failure) {
            throw new IOException("The saved signed transaction could not be validated; its file is preserved", failure);
        }
    }

    void clear(PreparedTransfer expected, List<String> addresses) throws IOException {
        PreparedTransfer current = load(addresses);
        if (!current.id.equals(expected.id) || !current.transactionJson.equals(expected.transactionJson)
                || !current.address.equals(expected.address) || current.amount != expected.amount || current.fee != expected.fee) {
            throw new IOException("The saved signed transaction changed; its file is preserved");
        }
        Files.delete(file);
        forceDirectory();
    }

    private void forceDirectory() throws IOException {
        // The JDK cannot open directories as file channels on Windows; the signed file itself is forced before broadcast.
        if (home.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try (FileChannel directory = FileChannel.open(home, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                directory.force(true);
            }
        }
    }
}
