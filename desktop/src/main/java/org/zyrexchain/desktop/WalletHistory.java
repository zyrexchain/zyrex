package org.zyrexchain.desktop;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ergoplatform.ErgoTreePredef$;
import org.ergoplatform.ZyrexAddressEncoder;

/** Native wallet history plus a bounded, private record of acknowledged desktop submissions. */
final class WalletHistory {
    private static final int RECORD_LIMIT = 250;
    private static final int FILE_LIMIT = 256 * 1024;
    // This delay belongs to the genesis-pinned public testnet monetary configuration.
    private static final String FEE_TREE = ErgoTreePredef$.MODULE$.feeProposition(5).bytesHex();
    private final Path home;
    private final Path file;
    private final Map<String, Map<String, Object>> submissions = new LinkedHashMap<>();
    private boolean loaded;
    private boolean writable = true;
    private String warning = "";

    WalletHistory(Path home) {
        this.home = home.toAbsolutePath().normalize();
        this.file = this.home.resolve("submitted-transactions.json");
    }

    synchronized String record(String id, String walletAddress, String recipient, long amount, long fee) {
        load();
        validateId(id);
        NodeApi.validateAddress(walletAddress);
        NodeApi.validateAddress(recipient);
        if (amount <= 0 || fee < Units.DEFAULT_FEE || amount > Long.MAX_VALUE - fee) {
            throw new IllegalArgumentException("Invalid submission amount or fee");
        }
        submissions.put(id, Json.object("id", id, "wallet", walletAddress, "recipient", recipient,
                "amount", amount, "fee", fee, "submittedAt", System.currentTimeMillis()));
        while (submissions.size() > RECORD_LIMIT) submissions.remove(submissions.keySet().iterator().next());
        if (writable) {
            try {
                save();
                warning = "";
            } catch (IOException failure) {
                warning = "Submission accepted, but its local history record could not be saved. Keep its transaction ID.";
            }
        }
        return warning;
    }

    synchronized String warning() { load(); return warning; }

    synchronized Row submittedRow(String id, List<String> addresses) {
        load();
        Set<String> own = new HashSet<>(addresses);
        Map<String, Object> submitted = submission(id, own);
        return submitted == null ? null : localRow(id, submitted, own);
    }

    View refresh(NodeApi api, List<String> addresses, View previous) throws InterruptedException {
        try {
            return new View(rows(api.transactions(), api.unconfirmedTransactions(), addresses), warning());
        } catch (IOException | RuntimeException failure) {
            Map<String, Row> retained = new LinkedHashMap<>();
            for (Row row : previous.rows) retained.put(row.id, row);
            try {
                for (Row row : rows(Collections.emptyList(), Collections.emptyList(), addresses)) retained.putIfAbsent(row.id, row);
            } catch (IOException | RuntimeException ignored) { }
            return new View(new ArrayList<>(retained.values()), "History refresh unavailable. Last successful history is preserved.");
        }
    }

    synchronized List<Row> rows(List<Map<String, Object>> confirmed, List<Map<String, Object>> pending,
            List<String> addresses) throws IOException {
        load();
        Set<String> own = new HashSet<>(addresses);
        Set<String> scripts = new HashSet<>();
        for (String address : addresses) {
            try {
                scripts.add(ZyrexAddressEncoder.apply((byte) 64).fromString(address).get().script().bytesHex());
            } catch (RuntimeException failure) {
                throw new IOException("The wallet returned an invalid history address", failure);
            }
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Map<String, Object> tx : confirmed) entries.put(id(tx), new Entry(tx, true));
        for (Map<String, Object> tx : pending) entries.putIfAbsent(id(tx), new Entry(tx, false));
        Map<String, BigInteger> ownedBoxes = new HashMap<>();
        for (Entry entry : entries.values()) {
            for (Map<String, Object> output : items(entry.tx, "outputs")) {
                if (owned(output, own, scripts)) {
                    String box = text(output, "boxId");
                    if (!box.isEmpty()) ownedBoxes.put(box, units(output));
                }
            }
        }
        List<Row> result = new ArrayList<>();
        for (Map.Entry<String, Entry> item : entries.entrySet()) {
            Map<String, Object> submitted = submission(item.getKey(), own);
            Row row = describe(item.getKey(), item.getValue(), submitted, own, scripts, ownedBoxes);
            if (row != null) result.add(row);
        }
        for (Map.Entry<String, Map<String, Object>> item : submissions.entrySet()) {
            if (!entries.containsKey(item.getKey()) && own.contains(text(item.getValue(), "wallet"))) {
                result.add(localRow(item.getKey(), item.getValue(), own));
            }
        }
        result.sort(Comparator.comparing((Row row) -> row.included)
                .thenComparing(Comparator.comparingInt((Row row) -> row.height).reversed())
                .thenComparing(Comparator.comparingLong((Row row) -> row.submittedAt).reversed()));
        return result;
    }

    private Map<String, Object> submission(String id, Set<String> own) {
        Map<String, Object> value = submissions.get(id);
        return value != null && own.contains(text(value, "wallet")) ? value : null;
    }

    private static Row describe(String id, Entry entry, Map<String, Object> submitted, Set<String> own,
            Set<String> scripts, Map<String, BigInteger> ownedBoxes) throws IOException {
        BigInteger outputs = BigInteger.ZERO;
        BigInteger external = BigInteger.ZERO;
        BigInteger fee = BigInteger.ZERO;
        for (Map<String, Object> output : items(entry.tx, "outputs")) {
            BigInteger value = units(output);
            if (owned(output, own, scripts)) outputs = outputs.add(value);
            else if (FEE_TREE.equals(text(output, "ergoTree"))) fee = fee.add(value);
            else external = external.add(value);
        }
        BigInteger inputs = BigInteger.ZERO;
        int ownedInputs = 0;
        List<Map<String, Object>> txInputs = items(entry.tx, "inputs");
        for (Map<String, Object> input : txInputs) {
            BigInteger value = ownedBoxes.get(text(input, "boxId"));
            if (value == null && owned(input, own, scripts) && input.get("value") != null) value = units(input);
            if (value != null) { inputs = inputs.add(value); ownedInputs++; }
        }
        if (!entry.confirmed && submitted == null && ownedInputs == 0 && outputs.signum() == 0) return null;
        int height = entry.confirmed ? integer(entry.tx, "inclusionHeight") : -1;
        if (entry.confirmed && height < 1) throw new IOException("The node returned an invalid included transaction height");
        int confirmations = entry.confirmed ? Math.addExact(integer(entry.tx, "numConfirmations"), 1) : 0;
        Row row = new Row(id, entry.confirmed, height, confirmations, submitted == null ? 0 : time(submitted));
        if (ownedInputs > 0 && ownedInputs == txInputs.size()) {
            row.direction = external.signum() == 0 ? "Self transfer" : "Sent";
            row.amount = external.signum() == 0 ? "—" : Units.format(external);
            row.fee = fee.signum() == 0 ? "—" : Units.format(fee);
            row.net = signed(outputs.subtract(inputs));
        } else if (submitted != null) {
            if (matchesSubmission(entry.tx, submitted, own, external, fee)) transfer(row, submitted, own);
        } else if (ownedInputs > 0) {
            row.direction = "Wallet activity";
            row.net = signed(outputs.subtract(inputs));
        } else if (outputs.signum() > 0) {
            row.direction = "Received";
            row.amount = Units.format(outputs);
            row.net = signed(outputs);
        }
        return row;
    }

    private static boolean matchesSubmission(Map<String, Object> tx, Map<String, Object> submitted, Set<String> own,
            BigInteger external, BigInteger fee) throws IOException {
        BigInteger amount = BigInteger.valueOf(Json.asLong(submitted.get("amount")));
        if (!fee.equals(BigInteger.valueOf(Json.asLong(submitted.get("fee"))))) return false;
        String recipient = text(submitted, "recipient");
        if (own.contains(recipient)) return external.signum() == 0;
        if (!external.equals(amount)) return false;
        String recipientScript = ZyrexAddressEncoder.apply((byte) 64).fromString(recipient).get().script().bytesHex();
        BigInteger received = BigInteger.ZERO;
        for (Map<String, Object> output : items(tx, "outputs")) {
            if (recipient.equals(text(output, "address")) || recipientScript.equals(text(output, "ergoTree"))) {
                received = received.add(units(output));
            }
        }
        return received.equals(amount);
    }

    private static Row localRow(String id, Map<String, Object> submitted, Set<String> own) {
        Row row = new Row(id, false, -1, 0, time(submitted));
        row.status = "Submitted · inclusion not found";
        row.sendStatus = "Submitted; inclusion not yet found. Check history before retrying.";
        transfer(row, submitted, own);
        return row;
    }

    private static void transfer(Row row, Map<String, Object> submitted, Set<String> own) {
        BigInteger amount = BigInteger.valueOf(Json.asLong(submitted.get("amount")));
        BigInteger fee = BigInteger.valueOf(Json.asLong(submitted.get("fee")));
        boolean internal = own.contains(text(submitted, "recipient"));
        row.direction = internal ? "Self transfer" : "Sent";
        row.amount = internal ? "—" : Units.format(amount);
        row.fee = Units.format(fee);
        row.net = signed((internal ? fee : amount.add(fee)).negate());
    }

    private static boolean owned(Map<String, Object> box, Set<String> own, Set<String> scripts) {
        return own.contains(text(box, "address")) || scripts.contains(text(box, "ergoTree"));
    }

    private static BigInteger units(Map<String, Object> box) throws IOException {
        try {
            long value = Json.asLong(box.get("value"));
            if (value < 0) throw new IllegalArgumentException("Negative output");
            return BigInteger.valueOf(value);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("The node returned an invalid history amount", failure);
        }
    }

    private static int integer(Map<String, Object> tx, String key) throws IOException {
        try { return Math.toIntExact(Json.asLong(tx.get(key))); }
        catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("The node returned invalid history inclusion metadata", failure);
        }
    }

    private static List<Map<String, Object>> items(Map<String, Object> tx, String key) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try { for (Object value : Json.asList(tx.get(key))) result.add(Json.asObject(value)); }
        catch (IllegalArgumentException failure) { throw new IOException("The node returned invalid history boxes", failure); }
        return result;
    }

    private static String id(Map<String, Object> tx) throws IOException {
        String id = text(tx, "id");
        try { validateId(id); }
        catch (IllegalArgumentException failure) { throw new IOException("The node returned an invalid history transaction ID", failure); }
        return id;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid transaction ID");
    }

    private static String text(Map<String, Object> value, String key) {
        Object item = value.get(key);
        return item instanceof String ? (String) item : "";
    }

    private static long time(Map<String, Object> value) { return Json.asLong(value.get("submittedAt")); }
    private static String signed(BigInteger value) { return (value.signum() > 0 ? "+" : "") + Units.format(value); }
    static String feeTree() { return FEE_TREE; }

    private void load() {
        if (loaded) return;
        loaded = true;
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        try {
            requireFile(file);
            Map<String, Object> data = Json.asObject(Json.parse(Files.readString(file)));
            if (Json.asLong(data.get("version")) != 1 || !NodeApi.TESTNET_GENESIS.equals(data.get("genesis"))
                    || !home.toString().equals(data.get("dataHome"))) throw new IOException("Submission history pin mismatch");
            List<Object> records = Json.asList(data.get("submissions"));
            if (records.size() > RECORD_LIMIT) throw new IOException("Too many saved submissions");
            Map<String, Map<String, Object>> checked = new LinkedHashMap<>();
            for (Object record : records) {
                Map<String, Object> value = Json.asObject(record);
                String id = text(value, "id");
                validateId(id);
                NodeApi.validateAddress(text(value, "wallet"));
                NodeApi.validateAddress(text(value, "recipient"));
                long amount = Json.asLong(value.get("amount"));
                long fee = Json.asLong(value.get("fee"));
                if (value.size() != 6 || amount <= 0 || fee < Units.DEFAULT_FEE || amount > Long.MAX_VALUE - fee
                        || time(value) < 0 || checked.put(id, value) != null) throw new IOException("Invalid saved submission");
            }
            submissions.putAll(checked);
        } catch (IOException | IllegalArgumentException | ArithmeticException failure) {
            writable = false;
            warning = "Saved submission history could not be read. Native history is shown; the saved file is preserved.";
        }
    }

    private void save() throws IOException {
        byte[] bytes = Json.stringify(Json.object("version", 1, "genesis", NodeApi.TESTNET_GENESIS,
                "dataHome", home.toString(), "submissions", new ArrayList<>(submissions.values()))).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > FILE_LIMIT) throw new IOException("Submission history exceeds its size limit");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) requireFile(file);
        Path temporary = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { requireFile(temporary); Files.delete(temporary); }
        Files.createFile(temporary);
        try {
            protect(temporary);
            Files.write(temporary, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) > FILE_LIMIT) {
            throw new IOException("Submission history must be a bounded regular private file");
        }
        protect(path);
    }

    private static void protect(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("Private file permissions are unavailable");
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(Collections.singletonList(entry));
    }

    static final class Row {
        final String id;
        final boolean included;
        final int height;
        final int confirmations;
        final long submittedAt;
        String direction = "Wallet activity";
        String amount = "—";
        String fee = "—";
        String net = "—";
        String status;
        String sendStatus;

        Row(String id, boolean included, int height, int confirmations, long submittedAt) {
            this.id = id; this.included = included; this.height = height;
            this.confirmations = confirmations; this.submittedAt = submittedAt;
            status = included ? "Included · block " + height + " · " + confirmations + " confirmation(s)" : "Pending · local mempool";
            sendStatus = included ? "Included in block " + height + " · " + confirmations + " confirmation(s)."
                    : "Pending in your node mempool. Waiting for a block.";
        }

        Object[] cells() { return new Object[] {id, direction, status, amount, fee, net}; }
    }

    static final class View {
        final List<Row> rows;
        final String warning;
        View(List<Row> rows, String warning) { this.rows = new ArrayList<>(rows); this.warning = warning; }
        static View empty() { return new View(Collections.emptyList(), ""); }
    }

    private static final class Entry {
        final Map<String, Object> tx;
        final boolean confirmed;
        Entry(Map<String, Object> tx, boolean confirmed) { this.tx = tx; this.confirmed = confirmed; }
    }
}
