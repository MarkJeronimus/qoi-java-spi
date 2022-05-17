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

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-14
public class QOIReadTestMain extends JPanel {
	private final BufferedImage image;

	public static void main(String... args) throws IOException {
		IIORegistry.getDefaultInstance().registerServiceProvider(new QOIImageReaderSpi());

		String filename = "qoi_test_images/dice.qoi";
		BufferedImage image = ImageIO.read(new File(filename));

		SwingUtilities.invokeLater(() -> {
			JFrame f = new JFrame(filename);
			f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

			f.setContentPane(new QOIReadTestMain(image));

			f.pack();
			f.setLocationRelativeTo(null);
			f.setVisible(true);
		});
	}

	@SuppressWarnings("OverridableMethodCallDuringObjectConstruction")
	public QOIReadTestMain(BufferedImage image) {
		super(null);
		setBackground(Color.WHITE);
		setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
		this.image = Objects.requireNonNull(image);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.drawImage(image, 0, 0, null);
	}
}
