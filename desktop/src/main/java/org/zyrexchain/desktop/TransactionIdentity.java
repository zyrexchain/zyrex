package org.zyrexchain.desktop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical native fields for old nodes whose API cannot derive a signed transaction ID. */
final class TransactionIdentity {
    private TransactionIdentity() { }

    static void requireSame(Map<String, Object> signed, Map<String, Object> observed) throws IOException {
        try {
            if (!canonical(signed).equals(canonical(observed))) {
                throw new IOException("The observed transaction differs from the saved signed transaction; its file is preserved");
            }
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw new IOException("The native transaction identity fields are missing or invalid; its file is preserved", failure);
        }
    }

    private static Map<String, Object> canonical(Map<String, Object> transaction) {
        List<Object> inputs = new ArrayList<>();
        for (Object item : Json.asList(transaction.get("inputs"))) {
            Map<String, Object> input = Json.asObject(item);
            Map<String, Object> proof = Json.asObject(input.get("spendingProof"));
            inputs.add(Json.object("boxId", hex(input.get("boxId"), 64, false), "spendingProof",
                Json.object("proofBytes", hex(proof.get("proofBytes"), 0, true),
                    "extension", constants(proof.get("extension"), false))));
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("Missing transaction inputs");
        List<Object> dataInputs = new ArrayList<>();
        for (Object item : Json.asList(transaction.get("dataInputs"))) {
            dataInputs.add(Json.object("boxId", hex(Json.asObject(item).get("boxId"), 64, false)));
        }
        List<Object> outputs = new ArrayList<>();
        for (Object item : Json.asList(transaction.get("outputs"))) {
            Map<String, Object> output = Json.asObject(item);
            List<Object> assets = new ArrayList<>();
            for (Object asset : Json.asList(output.get("assets"))) {
                Map<String, Object> token = Json.asObject(asset);
                assets.add(Json.object("tokenId", hex(token.get("tokenId"), 64, false),
                    "amount", positive(token.get("amount"))));
            }
            long height = Json.asLong(output.get("creationHeight"));
            if (height < 0 || height > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid output height");
            outputs.add(Json.object("value", positive(output.get("value")), "ergoTree", hex(output.get("ergoTree"), 0, false),
                "assets", assets, "creationHeight", height, "additionalRegisters", constants(output.get("additionalRegisters"), true)));
        }
        if (outputs.isEmpty()) throw new IllegalArgumentException("Missing transaction outputs");
        return Json.object("inputs", inputs, "dataInputs", dataInputs, "outputs", outputs);
    }

    private static Map<String, Object> constants(Object value, boolean registers) {
        Map<String, Object> source = Json.asObject(value);
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            if (registers) {
                if (!key.matches("R[4-9]")) throw new IllegalArgumentException("Invalid output register");
            } else {
                if (!key.matches("-?[0-9]{1,3}")) throw new IllegalArgumentException("Invalid context extension index");
                int index = Integer.parseInt(key);
                if (index < -128 || index > 127) throw new IllegalArgumentException("Invalid context extension index");
                key = Integer.toString(index);
            }
            if (canonical.put(key, hex(entry.getValue(), 0, false)) != null) {
                throw new IllegalArgumentException("Duplicate canonical context field");
            }
        }
        return canonical;
    }

    private static long positive(Object value) {
        long number = Json.asLong(value);
        if (number <= 0) throw new IllegalArgumentException("Invalid native amount");
        return number;
    }

    private static String hex(Object value, int exactLength, boolean emptyAllowed) {
        if (!(value instanceof String)) throw new IllegalArgumentException("Missing native byte field");
        String text = (String) value;
        if ((exactLength > 0 && text.length() != exactLength) || (text.length() & 1) != 0
                || (!emptyAllowed && text.isEmpty()) || !text.matches("[0-9a-fA-F]*")) {
            throw new IllegalArgumentException("Invalid native hexadecimal bytes");
        }
        return text.toLowerCase(Locale.ROOT);
    }
}
