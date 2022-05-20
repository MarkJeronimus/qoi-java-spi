import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * @author Zom-B
 */
// Created 2022-05-19
public class TileExtractorMain {
	private static final Map<Integer, BufferedImage> allTiles = new HashMap<>(10000);

	public static void main(String... args) throws IOException {
		Files.list(Paths.get("images")).forEach(TileExtractorMain::extractTiles);

		saveTiles();
	}

	private static void extractTiles(Path path) {
		if (!path.getFileName().toString().endsWith(".png")) {
			System.out.println("Unknown file found: " + path);
			return;
		}

		try {
			BufferedImage image = ImageIO.read(path.toFile());
			assert image != null : path;

			if (image.getWidth() < 50) {
				extractTiles(image, image.getWidth(), image.getHeight());
			} else {
				String filename = path.getFileName().toString();
				int    tileSize = Integer.parseInt(filename.substring(0, filename.indexOf('.')));
				extractTiles(image, tileSize, tileSize);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void extractTiles(BufferedImage image, int width, int height) {
		boolean hasAlpha  = image.getColorModel().hasAlpha();
		int     imageType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

		for (int y = 0; y < image.getHeight(); y += height) {
			for (int x = 0; x < image.getWidth(); x += width) {
				BufferedImage tileImage = new BufferedImage(width, height, imageType);
				Graphics2D    g         = tileImage.createGraphics();
				try {
					g.drawImage(image, -x, -y, null);
					addTile(tileImage);
				} finally {
					g.dispose();
				}
			}
		}
	}

	private static void addTile(BufferedImage image) {
		int[] pixels   = ((DataBufferInt)image.getRaster().getDataBuffer()).getData();
		int   hashCode = Arrays.hashCode(pixels);

		allTiles.put(hashCode, image);
	}

	private static void saveTiles() {
		for (Map.Entry<Integer, BufferedImage> entry : allTiles.entrySet()) {
			BufferedImage image = entry.getValue();
			if (!testImage(image)) {
				continue;
			}

			StringBuilder filename = new StringBuilder(Long.toString(entry.getKey() & 0xFFFFFFFFL, 16).toUpperCase());
			while (filename.length() < 8) {
				filename.insert(0, '0');
			}
			filename.append(".png");

			try {
				ImageIO.write(image, "png", new File("testsuite/" + filename));
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}
	}

	private static boolean testImage(BufferedImage image) {
		int[] pixels = ((DataBufferInt)image.getRaster().getDataBuffer()).getData();

		int[] sortedPixels = pixels.clone();
		Arrays.sort(sortedPixels);

		int lastPixel  = sortedPixels[0] + 1;
		int longestRun = 0;
		int currentRun = 0;
		int numColors  = 0;
		for (int i = 0; i < sortedPixels.length; i++) {
			int pixel = sortedPixels[i];
			if (lastPixel != pixel) {
				if (longestRun < currentRun) {
					longestRun = currentRun;
				}

				currentRun = 0;
				lastPixel = pixel;
				numColors++;
			}

			currentRun++;
		}
		if (longestRun < currentRun) {
			longestRun = currentRun;
		}

		return numColors > 2 && longestRun * longestRun > sortedPixels.length * 4;
	}
}
