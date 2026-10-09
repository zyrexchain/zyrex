package org.zyrexchain.desktop;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.bouncycastle.crypto.digests.Blake2bDigest;

/** Private RPC transport for the application's own verified testnet process. */
public final class NodeApi {
    public static final String TESTNET_GENESIS = "0f6e2d9181f10231dafa9e1aa3eb297218204e2e2c38f3c65ae0ab367629d336";
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private final URI endpoint;
    private final String key;
    private final BooleanSupplier processAlive;
    private final String nodeName;
    private final HttpClient http;

    NodeApi(int port, String key, BooleanSupplier processAlive) {
        this(port, key, processAlive, null);
    }

    NodeApi(int port, String key, BooleanSupplier processAlive, String nodeName) {
        if (port < 1 || port > 65535 || key == null || !key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid managed node connection");
        }
        this.endpoint = URI.create("http://127.0.0.1:" + port);
        this.key = key;
        this.processAlive = processAlive;
        this.nodeName = nodeName;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return Collections.singletonList(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    public Map<String, Object> info() throws IOException, InterruptedException {
        Map<String, Object> result = objectResponse(request("/info", null, false));
        if (!"testnet".equals(result.get("network")) || !TESTNET_GENESIS.equals(result.get("genesisBlockId"))
                || (nodeName != null && !nodeName.equals(result.get("name")))) {
            throw new IOException("The local node identity does not match Zyrex public testnet");
        }
        return result;
    }

    public Map<String, Object> status() throws IOException, InterruptedException {
        return objectResponse(request("/wallet/status", null, true));
    }

    public Map<String, Object> init(String password) throws IOException, InterruptedException {
        requireNewPassword(password);
        Map<String, Object> result = objectResponse(request("/wallet/init", Json.object("pass", password), true));
        Object mnemonic = result.get("mnemonic");
        if (!(mnemonic instanceof String)) {
            throw new IOException("Wallet initialization did not return its recovery phrase");
        }
        validateMnemonic((String) mnemonic);
        return result;
    }

    public void restore(String mnemonic, String password) throws IOException, InterruptedException {
        requireNewPassword(password);
        String normalized = validateMnemonic(mnemonic);
        request("/wallet/restore", Json.object("mnemonic", normalized, "pass", password, "usePre1627KeyDerivation", false), true);
        unlock(password);
        rescan();
    }

    public void unlock(String password) throws IOException, InterruptedException {
        requireSecret(password, "Password");
        if (Boolean.TRUE.equals(status().get("isUnlocked"))) {
            return;
        }
        try {
            request("/wallet/unlock", Json.object("pass", password), true);
        } catch (ApiException original) {
            try {
                if (Boolean.TRUE.equals(status().get("isUnlocked"))) {
                    return;
                }
            } catch (IOException ignored) {
                // Preserve the original failure when wallet state cannot be confirmed.
            }
            throw original;
        }
    }

    public void lock() throws IOException, InterruptedException { request("/wallet/lock", null, true); }

    public void rescan() throws IOException, InterruptedException {
        request("/wallet/rescan", Json.object("fromHeight", 1), true);
    }

    public List<String> addresses() throws IOException, InterruptedException {
        List<Object> items = listResponse(request("/wallet/addresses", null, true));
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String) || !((String) item).startsWith("ZRX")) {
                throw new IOException("The wallet returned an invalid testnet address");
            }
            result.add((String) item);
        }
        return result;
    }

    public String address() throws IOException, InterruptedException {
        Object address = objectResponse(request("/wallet/deriveNextKey", null, true)).get("address");
        if (!(address instanceof String) || !((String) address).startsWith("ZRX")) {
            throw new IOException("The wallet returned an invalid testnet address");
        }
        return (String) address;
    }

    public Map<String, Object> balance() throws IOException, InterruptedException {
        return objectResponse(request("/wallet/balances", null, true));
    }

    public Map<String, Object> balanceWithUnconfirmed() throws IOException, InterruptedException {
        return objectResponse(request("/wallet/balances/withUnconfirmed", null, true));
    }

    public List<Map<String, Object>> transactions() throws IOException, InterruptedException {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : listResponse(request("/wallet/transactions", null, true))) {
            result.add(objectResponse(item));
        }
        return result;
    }

    public String send(String address, long amount, long fee) throws IOException, InterruptedException {
        validateAddress(address);
        if (amount <= 0 || fee < Units.DEFAULT_FEE || amount > Long.MAX_VALUE - fee) {
            throw new IllegalArgumentException("Amount must be positive and the fee must be at least 0.001 ZYRX");
        }
        Object result = request("/wallet/transaction/send",
                Json.object("requests", Collections.singletonList(Json.object("address", address, "value", amount)), "fee", fee), true);
        if (!(result instanceof String) || !((String) result).matches("[0-9a-f]{64}")) {
            throw new IOException("The node did not return a valid transaction ID; check wallet history before retrying");
        }
        return (String) result;
    }

    public static void validateAddress(String address) {
        if (address == null || address.length() > 4096 || !address.matches("ZRX[1-9A-HJ-NP-Za-km-z]+")) {
            throw new IllegalArgumentException("Enter a Zyrex testnet address beginning with ZRX");
        }
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        String encoded = address.substring(3);
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < encoded.length(); i++) {
            value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(encoded.charAt(i))));
        }
        byte[] number = value.toByteArray();
        int signByte = number.length > 1 && number[0] == 0 ? 1 : 0;
        int zeroes = 0;
        while (zeroes < encoded.length() && encoded.charAt(zeroes) == '1') {
            zeroes++;
        }
        byte[] raw = new byte[zeroes + number.length - signByte];
        System.arraycopy(number, signByte, raw, zeroes, number.length - signByte);
        if (raw.length < 6) {
            throw new IllegalArgumentException("The recipient address is too short");
        }
        int type = (raw[0] & 255) - 64;
        if (type < 1 || type > 3 || (type == 1 && raw.length != 38)) {
            throw new IllegalArgumentException("The recipient address belongs to a different network or has an invalid length");
        }
        Blake2bDigest digest = new Blake2bDigest(256);
        digest.update(raw, 0, raw.length - 4);
        byte[] hash = new byte[32];
        digest.doFinal(hash, 0);
        for (int i = 0; i < 4; i++) {
            if (raw[raw.length - 4 + i] != hash[i]) {
                throw new IllegalArgumentException("The recipient address checksum is invalid; check its spelling");
            }
        }
    }

    void shutdown() throws IOException, InterruptedException {
        request("/node/shutdown", Collections.emptyMap(), true);
    }

    private Object request(String path, Object payload, boolean authenticated) throws IOException, InterruptedException {
        if (!processAlive.getAsBoolean()) {
            throw new IOException("The managed node is not running");
        }
        if (authenticated) {
            info();
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
        if (payload == null) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload), StandardCharsets.UTF_8));
        }
        if (authenticated) {
            builder.header("api_key", key);
        }
        CompletableFuture<HttpResponse<byte[]>> operation = http.sendAsync(builder.build(), ignored -> new LimitedBody());
        HttpResponse<byte[]> response;
        try {
            response = operation.get(35, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            operation.cancel(true);
            throw new IOException("The local node request timed out");
        } catch (ExecutionException e) {
            throw new IOException("Cannot communicate with the managed local node", e.getCause());
        } catch (InterruptedException e) {
            operation.cancel(true);
            throw e;
        }
        String text = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(response.statusCode(), safeError(path, response.statusCode(), text));
        }
        if (!processAlive.getAsBoolean()) {
            throw new IOException("The managed node stopped during the request");
        }
        try {
            return text.trim().isEmpty() ? null : Json.parse(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("The local node returned an invalid JSON response");
        }
    }

    private static String safeError(String path, int code, String text) {
        if (code >= 300 && code < 400) {
            return "The local node attempted a redirect; the request was blocked";
        }
        if (code == 401 || code == 403) {
            return "The local node rejected the application's private API credential";
        }
        String detail = "";
        try {
            Object value = Json.asObject(Json.parse(text)).get("detail");
            if (value instanceof String) {
                detail = ((String) value).toLowerCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) { }
        if (path.equals("/wallet/unlock")) {
            return "Cannot unlock the wallet. Check its password and initialization status";
        }
        if (detail.contains("locked")) {
            return "Unlock the wallet to continue";
        }
        if (detail.contains("not enough") || detail.contains("insufficient")) {
            return "The wallet has insufficient spendable balance for this amount and fee";
        }
        if (detail.contains("already initialized")) {
            return "A wallet already exists in this application data directory";
        }
        if (path.equals("/wallet/transaction/send")) {
            return "The node rejected the transaction. Check its recipient, spendable balance, fee and synchronization";
        }
        return "The local node rejected this operation (HTTP " + code + ")";
    }

    private static Map<String, Object> objectResponse(Object value) throws IOException {
        try {
            return Json.asObject(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("The local node returned an unexpected response");
        }
    }

    private static List<Object> listResponse(Object value) throws IOException {
        try {
            return Json.asList(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("The local node returned an unexpected response");
        }
    }

    private static void requireSecret(String secret, String name) {
        if (secret == null || secret.isEmpty() || secret.length() > 16384) {
            throw new IllegalArgumentException(name + " is empty or too long");
        }
    }

    private static void requireNewPassword(String password) {
        requireSecret(password, "Password");
        if (password.length() < 8) {
            throw new IllegalArgumentException("Use a wallet password with at least 8 characters");
        }
    }

    /** Generate a recovery phrase for backup confirmation before the wallet is stored. */
    public static String createRecoveryPhrase() throws IOException {
        byte[] entropy = new byte[32];
        try {
            new SecureRandom().nextBytes(entropy);
            return fromEntropy(entropy);
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    static String fromEntropy(byte[] entropy) throws IOException {
        if (entropy == null || entropy.length != 32) {
            throw new IllegalArgumentException("Wallet recovery phrases require 256 bits of entropy");
        }
        List<String> dictionary = englishWords();
        byte[] encoded = new byte[33];
        byte[] checksum = null;
        try {
            System.arraycopy(entropy, 0, encoded, 0, entropy.length);
            checksum = MessageDigest.getInstance("SHA-256").digest(entropy);
            encoded[32] = checksum[0];
            StringBuilder phrase = new StringBuilder();
            for (int word = 0; word < 24; word++) {
                int index = 0;
                for (int bit = 0; bit < 11; bit++) {
                    int position = word * 11 + bit;
                    index = (index << 1) | ((encoded[position / 8] >> (7 - position % 8)) & 1);
                }
                if (word > 0) {
                    phrase.append(' ');
                }
                phrase.append(dictionary.get(index));
            }
            return validateMnemonic(phrase.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("The bundled runtime cannot generate recovery phrases", e);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            if (checksum != null) {
                Arrays.fill(checksum, (byte) 0);
            }
        }
    }

    private static List<String> englishWords() throws IOException {
        List<String> words = new ArrayList<>();
        InputStream resource = NodeApi.class.getResourceAsStream("/wordlist/english.txt");
        if (resource == null) {
            throw new IOException("The bundled recovery phrase dictionary is missing");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            String word;
            while ((word = reader.readLine()) != null) {
                words.add(word);
            }
        }
        if (words.size() != 2048 || new java.util.HashSet<>(words).size() != 2048) {
            throw new IOException("The bundled recovery phrase dictionary is invalid");
        }
        return words;
    }

    static String validateMnemonic(String mnemonic) throws IOException {
        requireSecret(mnemonic, "Recovery phrase");
        String normalized = Normalizer.normalize(mnemonic, Normalizer.Form.NFKD)
                .trim().toLowerCase(Locale.ROOT).replaceAll("[\\p{javaWhitespace}\\p{Zs}]+", " ");
        String[] words = normalized.split(" ");
        if (words.length < 12 || words.length > 24 || words.length % 3 != 0) {
            throw new IllegalArgumentException("The English recovery phrase must contain 12, 15, 18, 21 or 24 words");
        }
        Map<String, Integer> dictionary = new HashMap<>();
        List<String> english = englishWords();
        for (int index = 0; index < english.size(); index++) {
            dictionary.put(english.get(index), index);
        }
        int totalBits = words.length * 11;
        int entropyBits = totalBits * 32 / 33;
        byte[] encoded = new byte[(totalBits + 7) / 8];
        byte[] entropy = new byte[entropyBits / 8];
        byte[] checksum = null;
        try {
            int cursor = 0;
            for (String word : words) {
                Integer index = dictionary.get(word);
                if (index == null) {
                    throw new IllegalArgumentException("The recovery phrase contains an unknown English BIP39 word");
                }
                for (int bit = 10; bit >= 0; bit--, cursor++) {
                    encoded[cursor / 8] |= ((index >> bit) & 1) << (7 - cursor % 8);
                }
            }
            System.arraycopy(encoded, 0, entropy, 0, entropy.length);
            checksum = MessageDigest.getInstance("SHA-256").digest(entropy);
            for (int bit = 0; bit < totalBits - entropyBits; bit++) {
                int position = entropyBits + bit;
                if (((encoded[position / 8] >> (7 - position % 8)) & 1) != ((checksum[0] >> (7 - bit)) & 1)) {
                    throw new IllegalArgumentException("The recovery phrase checksum is invalid; check its words and order");
                }
            }
            return normalized;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("The bundled runtime cannot verify recovery phrases", e);
        } finally {
            Arrays.fill(encoded, (byte) 0);
            Arrays.fill(entropy, (byte) 0);
            if (checksum != null) {
                Arrays.fill(checksum, (byte) 0);
            }
            Arrays.fill(words, null);
        }
    }

    public static final class ApiException extends IOException {
        private final int statusCode;
        private ApiException(int statusCode, String message) { super(message); this.statusCode = statusCode; }
        public int statusCode() { return statusCode; }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > RESPONSE_LIMIT - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Node response exceeds the supported size"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { body.completeExceptionally(error); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
