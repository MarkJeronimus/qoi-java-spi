import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.digitalmodular.qoi.QOIImageReaderSpi;
import org.digitalmodular.qoi.QOIImageWriterSpi;

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-14
public class QOIWriteTestMain extends JPanel {
	private final BufferedImage image1;
	private final BufferedImage image2;

	public static void main(String... args) throws IOException {
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageReaderSpi());
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageWriterSpi());

		String        filename1 = "pngsuite/basn3p08.png";
		String        filename2 = "temp.qoi";
		BufferedImage image1    = ImageIO.read(new File(filename1));
		ImageIO.write(image1, "qoi", new File(filename2));
		BufferedImage image2 = ImageIO.read(new File(filename2));

		SwingUtilities.invokeLater(() -> {
			JFrame f = new JFrame(filename1);
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

			f.setContentPane(new QOIWriteTestMain(image1, image2));

			f.pack();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	@SuppressWarnings("OverridableMethodCallDuringObjectConstruction")
	public QOIWriteTestMain(BufferedImage image1, BufferedImage image2) {
		super(null);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(image1.getWidth() * 2, image1.getHeight()));
		this.image1 = Objects.requireNonNull(image1);
		this.image2 = Objects.requireNonNull(image2);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.drawImage(image1, 0, 0, null);
		g.drawImage(image2, image1.getWidth(), 0, null);
	}
}
