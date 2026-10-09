package org.zyrexchain.desktop;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/** Real Swing layout regressions on the EDT; no wallet, node, display or funds are required. */
public final class SendLayoutTests {
    private static final String ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94";
    private static final String TRANSACTION = "0123456789abcdef".repeat(4);

    private SendLayoutTests() { }

    public static void main(String[] args) throws Exception {
        edt(() -> { Ui.install(); return null; });
        // Small logical viewports cover reduced usable area on 150% and 200% displays.
        for (Dimension size : new Dimension[] {
            new Dimension(430, 180), new Dimension(590, 280), new Dimension(770, 340),
            new Dimension(940, 510), new Dimension(1400, 800)
        }) {
            verify(size);
        }
        System.out.println("Desktop send layout: 5 viewport, success, error and refresh scenarios passed");
    }

    private static void verify(Dimension size) throws Exception {
        AtomicReference<long[]> confirmed = new AtomicReference<>();
        SendPanel panel = edt(() -> {
            SendPanel next = new SendPanel((address, amount, fee, success) -> {
                check(ADDRESS.equals(address), "Review must preserve the validated recipient");
                confirmed.set(new long[] {amount, fee});
                success.accept(TRANSACTION);
            });
            next.setSize(size);
            next.setReady(true, "", 5_000_000_000L);
            layout(next);
            return next;
        });
        edt(() -> {
            JScrollPane scroll = (JScrollPane) find(panel, "send-scroll");
            Scrollable view = (Scrollable) scroll.getViewport().getView();
            check(view.getScrollableTracksViewportWidth(), "Send content must follow viewport width");
            check(!view.getScrollableTracksViewportHeight(), "Send rows must retain preferred height");
            check(!scroll.getHorizontalScrollBar().isVisible(), "Send must not require horizontal scrolling");
            check(scroll.getViewport().getViewPosition().equals(new Point()),
                "A newly opened send form starts at the title: " + scroll.getViewport().getViewPosition());
            assertRows(panel);
            check(inViewport(panel, "send-title"), "The full send title must be visible at the top");
            text(panel, "send-recipient", ADDRESS);
            text(panel, "send-amount", "1.000000001");
            ((JButton) find(panel, "send-review")).doClick(0);
            layout(panel);
            return null;
        });
        edt(() -> {
            check(confirmed.get()[0] == 1_000_000_001L && confirmed.get()[1] == 1_000_000L,
                "Layout changes must preserve exact amount and additional fee validation");
            assertRows(panel);
            check(TRANSACTION.equals(((JTextComponent) find(panel, "sent-transaction")).getText()),
                "Successful submission must display the callback transaction ID");
            check(inViewport(panel, "copy-transaction"), "Submission must bring the whole copy button into view");
            JScrollPane scroll = (JScrollPane) find(panel, "send-scroll");
            if (size.height < scroll.getViewport().getViewSize().height) {
                check(scroll.getVerticalScrollBar().isVisible(), "Short viewports must offer a vertical scrollbar");
            }
            Point position = scroll.getViewport().getViewPosition();
            panel.setReady(true, "", 4_000_000_000L);
            panel.updateTransactionStatus(TRANSACTION, "Included in block 50 · 1 confirmation.", true);
            layout(panel);
            check(position.equals(scroll.getViewport().getViewPosition()), "Periodic wallet refresh must preserve scroll position");
            check(((JTextComponent) find(panel, "send-result")).getText().startsWith("Included in block 50"),
                "A submitted transaction must update to its observed inclusion state");
            panel.updateTransactionStatus("different-id", "Wrong transaction", true);
            check(((JTextComponent) find(panel, "send-result")).getText().startsWith("Included in block 50"),
                "Unrelated transaction updates must not replace the displayed result");
            panel.showError("The node could not complete this request. Check the recipient address, amount and network fee, "
                + "then review your wallet history before deciding whether to submit another transaction.");
            layout(panel);
            return null;
        });
        edt(() -> {
            assertRows(panel);
            JTextComponent result = (JTextComponent) find(panel, "send-result");
            String error = result.getText();
            panel.updateTransactionStatus(TRANSACTION, "Pending", false);
            check(error.equals(result.getText()), "A previous transaction refresh must not hide a later validation error");
            JScrollPane scroll = (JScrollPane) find(panel, "send-scroll");
            JPanel form = (JPanel) find(panel, "send-form");
            form.scrollRectToVisible(find(panel, "copy-transaction").getBounds());
            check(inViewport(panel, "copy-transaction"), "The full copy button must remain reachable after a wrapped error");
            scroll.getViewport().setViewPosition(new Point());
            check(inViewport(panel, "send-title"), "Scrolling back to the top must reveal the complete title");
            for (String name : new String[] {"send-recipient", "send-amount", "send-fee", "sent-transaction"}) {
                JTextField field = (JTextField) find(panel, name);
                Insets padding = field.getInsets();
                check(field.getHeight() - padding.top - padding.bottom >= field.getFontMetrics(field.getFont()).getHeight(),
                    "Send fields must leave enough inner height to paint their text");
            }
            return null;
        });
    }

    private static void assertRows(SendPanel panel) {
        JPanel form = (JPanel) find(panel, "send-form");
        Insets padding = form.getInsets();
        int bottom = padding.top;
        for (Component row : form.getComponents()) {
            if (!row.isVisible()) continue;
            Rectangle bounds = row.getBounds();
            check(bounds.x >= padding.left && bounds.x + bounds.width <= form.getWidth() - padding.right
                && bounds.y >= bottom && bounds.width > 0 && bounds.height > 0,
                "Visible send rows must retain complete nonnegative bounds without overlap");
            check(bounds.y + bounds.height <= form.getHeight() - padding.bottom, "Every row must fit within scrollable content");
            check(bounds.height >= row.getPreferredSize().height, "Send rows must not compress below their required text height");
            bottom = bounds.y + bounds.height;
        }
    }

    private static boolean inViewport(SendPanel panel, String name) {
        JScrollPane scroll = (JScrollPane) find(panel, "send-scroll");
        Component target = find(panel, name);
        Rectangle bounds = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), scroll.getViewport().getView());
        return target.isVisible() && scroll.getViewport().getViewRect().contains(bounds);
    }

    private static void text(Container root, String name, String value) {
        ((JTextComponent) find(root, name)).setText(value);
    }

    private static Component find(Container root, String name) {
        Component result = search(root, name);
        if (result == null) throw new AssertionError("Missing component " + name);
        return result;
    }

    private static Component search(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) {
                Component found = search((Container) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(Container root) {
        for (int pass = 0; pass < 3; pass++) layoutTree(root);
    }

    private static void layoutTree(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) if (child instanceof Container) layoutTree((Container) child);
    }

    private static <T> T edt(Callable<T> operation) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(operation);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
