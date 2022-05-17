import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;

import org.digitalmodular.qoi.QOIImageWriterSpi;

/**
 * @author Zom-B
 */
// Created 2022-05-17
public class TestMain {
	public static void main(String... args) throws IOException {
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageWriterSpi());

		Path         dir  = Paths.get("pngsuite/");
		Stream<Path> list = Files.list(dir);
		list.sorted().forEach(TestMain::checkImg);

		checkImg(new BufferedImage(32, 23, BufferedImage.TYPE_INT_RGB));
		checkImg(new BufferedImage(32, 23, BufferedImage.TYPE_INT_ARGB));
		checkImg(new BufferedImage(32, 23, BufferedImage.TYPE_INT_ARGB_PRE));
		checkImg(new BufferedImage(32, 23, BufferedImage.TYPE_INT_BGR));
		checkImg(new BufferedImage(32, 23, BufferedImage.TYPE_BYTE_GRAY));
	}

	private static void checkImg(Path path) {
		if (!path.getFileName().toString().endsWith(".png"))
			return;

		System.out.print(path.getFileName());

		try {
			BufferedImage image = ImageIO.read(path.toFile());
			checkImg(image);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}

	private static void checkImg(BufferedImage image) {
		try {
			ImageIO.write(image, "qoi", new File("temp.qoi"));
			System.out.println();
		} catch (UnsupportedOperationException ex) {
			System.out.println('\t' + ex.getMessage());
		} catch (IOException ex) {
			throw new RuntimeException(ex);
		}
	}
}
