import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Render the existing Zyrex vector mark into desktop packaging icons. */
public final class BrandIcons {
    private static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.scale(size / 128.0, size / 128.0);
        g.setColor(new Color(0x111923));
        g.fillRoundRect(0, 0, 128, 128, 56, 56);
        Path2D mark = new Path2D.Double();
        mark.moveTo(28, 30); mark.lineTo(102, 30); mark.lineTo(102, 46); mark.lineTo(52, 82);
        mark.lineTo(100, 82); mark.lineTo(100, 98); mark.lineTo(26, 98); mark.lineTo(26, 82);
        mark.lineTo(76, 46); mark.lineTo(28, 46); mark.closePath();
        g.setColor(new Color(0x71f5be)); g.fill(mark);
        Path2D accent = new Path2D.Double();
        accent.moveTo(94, 30); accent.lineTo(102, 30); accent.lineTo(102, 46);
        accent.lineTo(94, 52); accent.closePath();
        g.setColor(new Color(0xff7768)); g.fill(accent);
        g.dispose();
        return image;
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        ImageIO.write(render(256), "png", output.resolve("zyrex-icon.png").toFile());
        int[] sizes = {16, 32, 48, 256};
        byte[][] payloads = new byte[sizes.length][];
        int total = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            ImageIO.write(render(sizes[i]), "png", stream);
            payloads[i] = stream.toByteArray(); total += payloads[i].length;
        }
        ByteBuffer ico = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            ico.put((byte) sizes[i]).put((byte) sizes[i]).put((byte) 0).put((byte) 0);
            ico.putShort((short) 1).putShort((short) 32).putInt(payloads[i].length).putInt(offset);
            offset += payloads[i].length;
        }
        for (byte[] payload : payloads) ico.put(payload);
        Files.write(output.resolve("zyrex-icon.ico"), ico.array());
    }
}
