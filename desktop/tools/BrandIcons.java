import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Resize the supplied Zyrex logo without altering its artwork or background. */
public final class BrandIcons {
    private BrandIcons() { }

    private static BufferedImage render(BufferedImage source, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return image;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: BrandIcons SOURCE_JPEG OUTPUT_DIRECTORY");
        System.setProperty("java.awt.headless", "true");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null || source.getWidth() < 1 || source.getWidth() != source.getHeight()) {
            throw new IOException("The logo source must be a readable square image");
        }
        Path output = Path.of(args[1]);
        Files.createDirectories(output);
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        byte[][] payloads = new byte[sizes.length][];
        int total = 6 + 16 * sizes.length;
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            if (!ImageIO.write(render(source, sizes[i]), "png", stream)) throw new IOException("PNG image encoding is unavailable");
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
        Files.write(output.resolve("zyrex-icon.png"), payloads[payloads.length - 1]);
        Files.write(output.resolve("zyrex-icon.ico"), ico.array());
    }
}
