package org.zyrexchain.desktop;

import java.awt.Component;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Prepare, review, persist and submit on the window's existing serialized worker. */
final class SendController {
    @FunctionalInterface interface Actions {
        <T> void run(String description, Callable<T> work, Consumer<T> success);
    }

    private final Component window;
    private final SendPanel form;
    private final WalletHistory history;
    private final PendingTransferStore pending;
    private final Supplier<NodeApi> api;
    private final Supplier<List<String>> addresses;
    private final BooleanSupplier ready;
    private final LongSupplier balance;
    private final Actions actions;

    SendController(Component window, SendPanel form, WalletHistory history, PendingTransferStore pending, Supplier<NodeApi> api,
            Supplier<List<String>> addresses, BooleanSupplier ready, LongSupplier balance, Actions actions) {
        this.window = window;
        this.form = form;
        this.history = history;
        this.pending = pending;
        this.api = api;
        this.addresses = addresses;
        this.ready = ready;
        this.balance = balance;
        this.actions = actions;
    }

    boolean hasPending() { return pending.exists(); }

    void review(String address, long amount, long fee, Consumer<String> success) {
        if (!ready.getAsBoolean()) return;
        if (hasPending()) {
            form.showError("Check or retry the saved transaction before preparing another transfer.");
            return;
        }
        long revision = form.reviewRevision();
        actions.run("Preparing and checking your transaction fee…", () -> api.get().prepareTransfer(address, amount, fee), transfer -> {
            if (!validReview(transfer, revision)) return;
            if (transfer.confirm(window) && validReview(transfer, revision)) submit(transfer, false, success);
        });
    }

    void retry(Consumer<String> success) {
        if (!ready.getAsBoolean()) return;
        actions.run("Checking the saved transaction in wallet history and mempool…", () -> {
            PreparedTransfer transfer = pending.load(addresses.get());
            if (api.get().observedTransaction(transfer)) {
                pending.clear(transfer, addresses.get());
                return new Recovery(transfer, true);
            }
            api.get().checkPrepared(transfer);
            return new Recovery(transfer, false);
        }, recovery -> {
            if (recovery.observed) {
                success.accept(recovery.transfer.id);
                return;
            }
            if (ready.getAsBoolean() && recovery.transfer.confirm(window)) submit(recovery.transfer, true, success);
        });
    }

    private boolean validReview(PreparedTransfer transfer, long revision) {
        try {
            transfer.requireFresh();
            if (revision != form.reviewRevision() || !ready.getAsBoolean()
                    || Math.addExact(transfer.amount, transfer.fee) > balance.getAsLong()) {
                form.showError("The form or wallet state changed. Review the transaction again before sending.");
                return false;
            }
            return true;
        } catch (IOException | ArithmeticException failure) {
            form.showError(failure.getMessage());
            return false;
        }
    }

    private void submit(PreparedTransfer transfer, boolean retry, Consumer<String> success) {
        actions.run("Submitting the reviewed signed transaction…", () -> {
            api.get().checkPrepared(transfer);
            List<String> own = addresses.get();
            if (retry) {
                if (!pending.load(own).transactionJson.equals(transfer.transactionJson)) {
                    throw new IOException("The saved signed transaction changed; its file is preserved");
                }
            } else pending.save(transfer, own.get(0));
            String warning = history.record(transfer.id, own.get(0), transfer.address, transfer.amount, transfer.fee);
            if (!warning.isEmpty()) throw new IOException("Cannot save transaction history; nothing was submitted. " + warning);
            String id = api.get().broadcastPrepared(transfer);
            pending.clear(transfer, own);
            return id;
        }, success);
    }

    private static final class Recovery {
        final PreparedTransfer transfer;
        final boolean observed;
        Recovery(PreparedTransfer transfer, boolean observed) { this.transfer = transfer; this.observed = observed; }
    }
}
