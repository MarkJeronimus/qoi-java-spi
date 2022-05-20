import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;

import org.digitalmodular.qoi.QOI2ImageReaderSpi;
import org.digitalmodular.qoi.QOI2ImageWriterSpi;
import org.digitalmodular.qoi.QOIImageReaderSpi;
import org.digitalmodular.qoi.QOIImageWriterSpi;

/**
 * @author Zom-B
 */
// Created 2022-05-18
public class BenchmarkCompressionMain {
	private static final Map<Integer, int[]> countColors = new HashMap<>(65536);
	private static final Map<Integer, int[]> countAlphas = new HashMap<>(65536);

	public static void main(String... args) throws IOException {
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageReaderSpi());
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageWriterSpi());
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOI2ImageReaderSpi());
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOI2ImageWriterSpi());

		List<Path> files = Files.list(Paths.get("testsuite"))
		                        .sorted()
//		                        .limit(100)
                                .collect(Collectors.toList());

		long  t    = System.nanoTime();
		int[] sums = new int[3];
		for (Path file : files) {
			if (!file.getFileName().toString().endsWith(".png")) {
				continue;
			}

			int[] results = testImage(file);

			for (int i = 0; i < sums.length; i++) {
				sums[i] += results[i];
			}
		}

		float qoiRate  = sums[1] * 100.0f / sums[0];
		float qoi2Rate = sums[2] * 100.0f / sums[0];
		System.out.printf("Totals: %.2f\t%.2f\t%.2f\t%.2f\n\n",
		                  qoiRate,
		                  qoi2Rate,
		                  qoi2Rate * 100.0f / qoiRate,
		                  (System.nanoTime() - t) / 1.0e6f);

//		System.out.println("Colors:");
//		dumpCounts(countColors);
//		System.out.println("Alphas:");
//		dumpCounts(countAlphas);
	}

	private static void dumpCounts(Map<Integer, int[]> countColors) {
		TreeMap<Integer, List<Integer>> reversedCounts = new TreeMap<>();
		for (Map.Entry<Integer, int[]> integerEntry : countColors.entrySet()) {
			int color = integerEntry.getKey();
			int count = integerEntry.getValue()[0];
			reversedCounts.computeIfAbsent(count, ignored -> new ArrayList<>(16)).add(color);
		}
		int i = 0;
		for (Integer count : reversedCounts.descendingKeySet()) {
			System.out.print(count);
			List<Integer> colors = reversedCounts.get(count);

			for (Integer color : colors) {
				StringBuilder hex = new StringBuilder(Long.toString(color & 0xFFFFFFFFL, 16).toUpperCase());
				while (hex.length() < 2) {
					hex.insert(0, '0');
				}
				System.out.print(" " + hex);
			}
			System.out.println();
			i++;
			if (i == 100)
				break;
		}
	}

	private static int[] testImage(Path file) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream(10000000);
		try {
			BufferedImage image   = ImageIO.read(file.toFile());
			int           srcSize = image.getWidth() * image.getHeight() * 3;

//			countColors(image);

			ImageIO.write(image, "qoi", stream);
			int qoiSize = stream.size();
			stream.reset();

			ImageIO.write(image, "qoi2", stream);
			int qoi2Size = stream.size();
			stream.reset();

			System.out.printf("%-22.22s\t%d\t%d\t%s\t%d\t%d\t%.5f\n",
			                  file,
			                  srcSize,
			                  Files.size(file),
			                  image.getColorModel().hasAlpha() ? "RGBA" : "RGB",
			                  qoiSize,
			                  qoi2Size,
			                  qoi2Size / (float)qoiSize);
			return new int[]{srcSize, qoiSize, qoi2Size};
		} catch (IOException ex) {
			System.out.printf("%-80.80s%s\n", file, ex.getMessage());
			return new int[]{0, 0, 0};
		}
	}

	private static void countColors(BufferedImage image) {
		BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		{
			Graphics2D g = copy.createGraphics();
			try {
				g.drawImage(image, 0, 0, null);
			} finally {
				g.dispose();
			}
		}
		int[] pixels = ((DataBufferInt)copy.getRaster().getDataBuffer()).getData();
		for (int color : pixels) {
			int a = (color >> 24) & 0xFF;
			int r = (color >> 16) & 0xFF;
			int g = (color >> 8) & 0xFF;
			int b = color & 0xFF;
			countAlphas.computeIfAbsent(a, ignored -> new int[1])[0]++;
			countColors.computeIfAbsent(r, ignored -> new int[1])[0]++;
			countColors.computeIfAbsent(g, ignored -> new int[1])[0]++;
			countColors.computeIfAbsent(b, ignored -> new int[1])[0]++;
		}
	}
}
