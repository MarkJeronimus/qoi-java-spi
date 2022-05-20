import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.digitalmodular.qoi.QOIImageReaderSpi;
import org.digitalmodular.qoi.QOIImageWriterSpi;

/**
 * I run this with {@code -Dsun.java2d.uiScale=8} because I'm too cheap to implement scaling in a cheap test class.
 *
 * @author Mark Jeronimus
 */
// Created 2022-05-14
@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace"})
public class QOIWriteTestMain extends JPanel {
	@SuppressWarnings({"StaticVariableMayNotBeInitialized", "StaticCollection"})
	private static List<Path> files;
	private static int        fileIndex = 0;

	private BufferedImage image1 = null;
	private BufferedImage image2 = null;

	public static void main(String... args) throws IOException {
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageReaderSpi());
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageWriterSpi());

		// QOI Encoding business is in convertImage()

//		Path dir = Paths.get("pngsuite/");
//		Path dir = Paths.get("qoi_test_images/");
		Path dir = Paths.get("testsuite/");

		try (Stream<Path> stream = Files.list(dir)) {
			files = stream.filter(file -> !Files.isDirectory(file))
			              .filter(file -> file.getFileName().toString().endsWith(".png"))
			              .sorted()
			              .collect(Collectors.toList());
		}

		if (files.isEmpty()) {
			System.err.println("No files found in " + dir.toAbsolutePath());
			System.exit(1);
		}

		fileIndex = ThreadLocalRandom.current().nextInt(files.size());
//		fileIndex = IntStream.range(0, files.size())
//		                     .filter(i -> {
//			                     Path file = files.get(i);
//			                     return file.getFileName().toString().startsWith("E78DD866");
//		                     })
//		                     .findFirst()
//		                     .orElse(0);

		SwingUtilities.invokeLater(() -> {
			JFrame f = new JFrame();
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

			f.setContentPane(new QOIWriteTestMain());

			f.pack();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	@SuppressWarnings("OverridableMethodCallDuringObjectConstruction")
	public QOIWriteTestMain() {
		super(null);
		setBackground(Color.WHITE);

		fileIndex %= files.size();
		convertImage();
		assert image1 != null;

		setPreferredSize(new Dimension(512, 256));

		// Yes I know it's bad practice to do work on the EDT, but it's a cheap test class anyway.

		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					fileIndex = (fileIndex + 1) % files.size();
				} else if (e.getButton() == MouseEvent.BUTTON3) {
					fileIndex = (fileIndex + files.size() - 1) % files.size();
				}

				// Yes I know it's bad practice to do work on the EDT, but it's a cheap test class anyway.
				convertImage();

				repaint();
			}
		});
	}

	private void convertImage() {
		String format   = "qoi";
		Path   file     = files.get(fileIndex);
		String filename = file.getFileName().toString();
		filename = filename.substring(0, filename.length() - 4) + '.' + format;
		File qoiFile = file.getParent().resolve(filename).toFile();

		try {
			image1 = ImageIO.read(file.toFile());              // PNG
			ImageIO.write(image1, format, qoiFile); // QOI
			image2 = ImageIO.read(qoiFile);                    // QOI
		} catch (IOException ex) {
			ex.printStackTrace();
			System.exit(1);
		}

		byte[] pixels1 = ((DataBufferByte)image1.getRaster().getDataBuffer()).getData();
		byte[] pixels2 = ((DataBufferByte)image2.getRaster().getDataBuffer()).getData();
		if (!Arrays.equals(pixels1, pixels2))
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
			                                                               "Images differ!",
			                                                               getClass().getSimpleName(),
			                                                               JOptionPane.ERROR_MESSAGE));

		Frame topLevelAncestor = (Frame)getTopLevelAncestor();
		if (topLevelAncestor != null) {
			topLevelAncestor.setTitle(fileIndex + ": " + filename);
		}
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		if (image1 == null)
			return;

		drawTransparencyCheckerboard(g);

		g.drawImage(image1,
		            0, 0, getWidth() / 2, getHeight(),
		            0, 0, image1.getWidth(), image1.getHeight(),
		            null);
		g.drawImage(image2,
		            getWidth() / 2, 0, getWidth(), getHeight(),
		            0, 0, image2.getWidth(), image2.getHeight(),
		            null);
	}

	private void drawTransparencyCheckerboard(Graphics g) {
		int blockSize = 16;
		for (int y = 0; y < getWidth(); y += blockSize) {
			boolean b = (y / blockSize & 1) != 0;
			for (int x = 0; x < getWidth(); x += blockSize) {
				g.setColor(b ? Color.LIGHT_GRAY : Color.GRAY);
				g.fillRect(x, y, blockSize, blockSize);
				b = !b;
			}
		}
	}
}
