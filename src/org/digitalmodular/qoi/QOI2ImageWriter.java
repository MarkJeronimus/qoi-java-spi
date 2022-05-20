package org.digitalmodular.qoi;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import javax.imageio.IIOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-16
@SuppressWarnings({"ConstantConditions", "OverlyComplexClass", "ReturnOfNull", "UseOfSystemOutOrSystemErr"})
public final class QOI2ImageWriter extends ImageWriter {
	@SuppressWarnings("CharUsedInArithmeticContext")
	static final int QOI_MAGIC = (('q' << 8 | 'o') << 8 | 'i') << 8 | '2'; // "qoi2", big-endian

	@SuppressWarnings("PublicField")
	public static boolean debugging = false;

	static final int QOI_OP_RGBA  = 0b11111111; // 11111111 R_______ G_______ B_______ A_______
	static final int QOI_OP_RGB   = 0b11111110; // 11111110 R_______ G_______ B_______
	static final int QOI_OP_RUN   = 0b11000000; // 11Repeat (62 values)
	static final int QOI_OP_LUMA  = 0b10000000; // 10Dy____ Du__Dv__
	static final int QOI_OP_DIFF  = 0b01000000; // 01DrDgDb
	static final int QOI_OP_INDEX = 0b00000000; // 00Index_

	private ImageOutputStream stream = null;

	// QOI header data
	private int width      = 0;
	private int height     = 0;
	private int channels   = 0;
	private int colorSpace = 0; // Currently unused

	// QOI encoder state
	private       byte     lastR             = 0;
	private       byte     lastG             = 0;
	private       byte     lastB             = 0;
	private       byte     lastA             = (byte)255;
	private       int      repeatCount       = 0;
	private final byte[][] recentColorsList  = new byte[64][4];
	private       int      recentColorsIndex = 0;

	// State for the progress reports
	/** Number of pixels to write */
	private int totalPixels  = 0;
	/** Number of pixels write */
	private int pixelsDone   = 0;
	/** Notify image observers once per this amount of work */
	private int nextUpdateAt = 0;

	// Fun statistics
	private       int   statRGBA  = 0;
	private       int   statRGB   = 0;
	private final int[] statRun   = new int[62];
	private       int   statLuma  = 0;
	private       int   statDiff  = 0;
	private       int   statIndex = 0;

	public QOI2ImageWriter(ImageWriterSpi originatingProvider) {
		super(originatingProvider);
	}

	@Override
	public void setOutput(Object output) {
		super.setOutput(output);

		if (Objects.equals(stream, output)) {
			return;
		}

		if (output != null && !(output instanceof ImageOutputStream)) {
			throw new IllegalArgumentException("output not an ImageOutputStream!");
		}

		stream = (ImageOutputStream)output;
	}

	@Override
	public ImageWriteParam getDefaultWriteParam() {
		return null;
	}

	@Override
	public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
		return null;
	}

	@Override
	public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType, ImageWriteParam param) {
		return null;
	}

	@Override
	public IIOMetadata convertStreamMetadata(IIOMetadata inData, ImageWriteParam param) {
		return null;
	}

	@Override
	public IIOMetadata convertImageMetadata(IIOMetadata inData, ImageTypeSpecifier imageType, ImageWriteParam param) {
		return null;
	}

	@Override
	public void write(IIOMetadata streamMetadata, IIOImage image, ImageWriteParam param) throws IIOException {
		if (image == null) {
			throw new IllegalArgumentException("image == null!");
		} else if (stream == null) {
			throw new IllegalStateException("output == null!");
		} else if (image.hasRaster()) {
			throw new UnsupportedOperationException("IIOImage has a Raster!");
		}

		RenderedImage renderedImage = image.getRenderedImage();
		ColorModel    colorModel    = renderedImage.getColorModel();
		boolean       hasAlpha      = colorModel.hasAlpha();

		width = renderedImage.getWidth();
		height = renderedImage.getHeight();
		channels = hasAlpha ? 4 : 3;
		colorSpace = 0;
		repeatCount = 0;
		lastR = 0;
		lastG = 0;
		lastB = 0;
		lastA = (byte)255;

		if (channels < 1 || channels > 4) {
			throw new UnsupportedOperationException("Cannot encode image with " + channels + " channels");
		}

		try {
			clearAbortRequest();
			processImageStarted(0);
			if (abortRequested()) {
				processWriteAborted();
			} else {
				writeHeader();
				encodeImage(renderedImage);
				writeFooter();

				if (abortRequested()) {
					processWriteAborted();
				} else {
					processImageComplete();
				}
			}
		} catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new IIOException("I/O error writing QOI data", ex);
		} catch (Throwable ex) {
			throw new IIOException("Unexpected exception during write", ex);
		}
	}

	private void writeHeader() throws IOException {
		stream.writeInt(QOI_MAGIC);
		stream.writeInt(width);
		stream.writeInt(height);
		stream.writeByte(channels);
		stream.writeByte(colorSpace);
	}

	private void encodeImage(RenderedImage image) throws IOException {
		// Prepare progress notification variables
		totalPixels = width * height;
		pixelsDone = 0;
		nextUpdateAt = 0;

		ColorModel colorModel  = image.getColorModel();
		int[]      sampleSizes = image.getSampleModel().getSampleSize();
		int        srcChannels = sampleSizes.length;

		boolean byteSamples = true;
		for (int sampleSize : sampleSizes) {
			if (sampleSize != 8) {
				byteSamples = false;
				break;
			}
		}

		if (byteSamples && colorModel instanceof DirectColorModel) {
			encodeDirectColorModelImage(image.getData(), srcChannels);
		} else if (byteSamples && colorModel instanceof ComponentColorModel) {
			encodeComponentColorModelImage(image.getData(), srcChannels);
		} else {
			encodeIncompatibleImage(image);
		}
	}

	private void encodeIncompatibleImage(RenderedImage image) throws IOException {
		int           imageType      = channels == 4 ? BufferedImage.TYPE_4BYTE_ABGR : BufferedImage.TYPE_3BYTE_BGR;
		BufferedImage convertedImage = new BufferedImage(width, height, imageType);

		Graphics2D g = convertedImage.createGraphics();
		try {
			g.drawRenderedImage(image, new AffineTransform());
		} finally {
			g.dispose();
		}

		encodeComponentColorModelImage(convertedImage.getRaster(), channels);
	}

	private void encodeDirectColorModelImage(Raster raster, int srcChannels) throws IOException {
		int[] pixels     = ((DataBufferInt)raster.getDataBuffer()).getData();
		int[] bitOffsets = ((SinglePixelPackedSampleModel)raster.getSampleModel()).getBitOffsets();
		int   lineStride = width * channels;

		if (srcChannels == 3 && channels == 3) {
			for (int i = 0; i < pixels.length; i++) {
				if (checkUpdateAndAbort(i, lineStride)) {
					break;
				}

				int  pixel = pixels[i];
				byte r     = (byte)(pixel >> bitOffsets[0]);
				byte g     = (byte)(pixel >> bitOffsets[1]);
				byte b     = (byte)(pixel >> bitOffsets[2]);
				encodeColor(r, g, b, (byte)255, i);
			}
		} else if (srcChannels == 4 && channels == 4) {
			for (int i = 0; i < pixels.length; i++) {
				if (checkUpdateAndAbort(i, lineStride)) {
					break;
				}

				int  pixel = pixels[i];
				byte r     = (byte)(pixel >> bitOffsets[0]);
				byte g     = (byte)(pixel >> bitOffsets[1]);
				byte b     = (byte)(pixel >> bitOffsets[2]);
				byte a     = (byte)(pixel >> bitOffsets[3]);
				encodeColor(r, g, b, a, i);
			}
		} else if (channels == 4) {
			throw new UnsupportedOperationException(
					"Cannot encode image with DirectColorModel, " + srcChannels + " channels, and alpha");
		} else {
			throw new UnsupportedOperationException(
					"Cannot encode image with DirectColorModel and " + srcChannels + " channels");
		}
	}

	private void encodeComponentColorModelImage(Raster raster, int srcChannels) throws IOException {
		byte[] samples     = ((DataBufferByte)raster.getDataBuffer()).getData();
		int[]  bandOffsets = ((ComponentSampleModel)raster.getSampleModel()).getBandOffsets();
		int    lineStride  = width * channels;
		int    p           = 0;

		if (srcChannels == 1 && channels == 3) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				byte sample = samples[p];
				encodeColor(sample, sample, sample, (byte)255, p);
				p++;
			}
		} else if (srcChannels == 2 && channels == 4) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				byte y = samples[p + bandOffsets[0]];
				byte a = samples[p + bandOffsets[1]];
				encodeColor(y, y, y, a, p);
				p += 2;
			}
		} else if (srcChannels == 3 && channels == 3) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				byte r = samples[p + bandOffsets[0]];
				byte g = samples[p + bandOffsets[1]];
				byte b = samples[p + bandOffsets[2]];
				encodeColor(r, g, b, (byte)255, p);
				p += 3;
			}
		} else if (srcChannels == 4 && channels == 4) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				byte r = samples[p + bandOffsets[0]];
				byte g = samples[p + bandOffsets[1]];
				byte b = samples[p + bandOffsets[2]];
				byte a = samples[p + bandOffsets[3]];
				encodeColor(r, g, b, a, p);
				p += 4;
			}
		} else if (channels == 4) {
			throw new UnsupportedOperationException(
					"Cannot encode image with ComponentColorModel, " + srcChannels + " channels, and alpha");
		} else {
			throw new UnsupportedOperationException(
					"Cannot encode image with ComponentColorModel and " + srcChannels + " channels");
		}
	}

	private void encodeColor(byte r, byte g, byte b, byte a, int p) throws IOException {
		boolean recordRecent = true;

		if (lastR == r && lastG == g && lastB == b && lastA == a) {
			repeatCount++;
			if (repeatCount == 62) {
				saveOpRun();
			}
			recordRecent = p == 0;
		} else {
			if (repeatCount != 0) {
				saveOpRun();
			}

			int recentColorIndex = findRecentColor(r, g, b, a);
			if (recentColorIndex >= 0) {
				saveOpIndex((byte)recentColorIndex);
				recordRecent = false;
			} else if (lastA != a) {
				saveOpRGBA(r, g, b, a);
			} else {
				byte dr = (byte)(r - lastR);
				byte dg = (byte)(g - lastG);
				byte db = (byte)(b - lastB);

				if (dg >= -2 && dg < 2 && // Ordered by largest chance to fail this test
				    dr >= -2 && dr < 2 &&
				    db >= -2 && db < 2) {
					saveOpDiff(dr, dg, db);
				} else {
					//        // dg is now dy (Y in YUV)
					dr -= dg; // dr is now du (U in YUV)
					db -= dg; // db is now dv (V in YUV)

					if (dr >= -8 && dr < 8 && // Ordered by largest chance to fail this test
					    db >= -8 && db < 8 &&
					    dg >= -32 && dg < 32) {
						saveOpLuma(dg, dr, db);
					} else {
						saveOpRGB(r, g, b);
					}
				}
			}
		}

		lastR = r;
		lastG = g;
		lastB = b;
		lastA = a;

		if (recordRecent) {
			recentColorsList[recentColorsIndex][0] = r;
			recentColorsList[recentColorsIndex][1] = g;
			recentColorsList[recentColorsIndex][2] = b;
			recentColorsList[recentColorsIndex][3] = a;
			recentColorsIndex = (recentColorsIndex + 1) & 63;
		}
	}

	private int findRecentColor(byte r, byte g, byte b, byte a) {
		for (int i = 0; i < 64; i++) {
			if (recentColorsList[i][0] == r && recentColorsList[i][1] == g &&
			    recentColorsList[i][2] == b && recentColorsList[i][3] == a) {
				return i;
			}
		}
		return -1;
	}

	private void saveOpRGBA(byte r, byte g, byte b, byte a) throws IOException {
		statRGBA++;
		if (debugging)
			System.out.printf("OP_RGBA (%3d, %3d, %3d, %3d)\n", r & 0xFF, g & 0xFF, b & 0xFF, a & 0xFF);
		stream.writeByte(QOI_OP_RGBA);
		stream.writeByte(r);
		stream.writeByte(g);
		stream.writeByte(b);
		stream.writeByte(a);
	}

	private void saveOpRGB(byte r, byte g, byte b) throws IOException {
		statRGB++;
		if (debugging)
			System.out.printf("OP_RGB  (%3d, %3d, %3d)\n", r & 0xFF, g & 0xFF, b & 0xFF);
		stream.writeByte(QOI_OP_RGB);
		stream.writeByte(r);
		stream.writeByte(g);
		stream.writeByte(b);
	}

	private void saveOpRun() throws IOException {
		statRun[repeatCount - 1]++;
		if (debugging)
			System.out.printf("OP_RUN  (%3d)\n", repeatCount - 1);
		stream.writeByte(QOI_OP_RUN | (repeatCount - 1));
		repeatCount = 0;
	}

	private void saveOpLuma(byte dy, byte du, byte dv) throws IOException {
		statLuma++;
		if (debugging)
			System.out.printf("OP_LUMA (%3d, %3d, %3d)\n", dy, du, dv);
		stream.writeByte(QOI_OP_LUMA | (dy + 32));
		stream.writeByte((du + 8) << 4 | (dv + 8));
	}

	private void saveOpDiff(byte dr, byte dg, byte db) throws IOException {
		statDiff++;
		if (debugging)
			System.out.printf("OP_DIFF (%3d, %3d, %3d)\n", dg, dr, db);
		stream.writeByte(QOI_OP_DIFF | (dr + 2) << 4 | (dg + 2) << 2 | (db + 2));
	}

	private void saveOpIndex(byte index) throws IOException {
		statIndex++;
		if (debugging)
			System.out.printf("OP_INDEX(%3d)\n", index);
		stream.writeByte(QOI_OP_INDEX | index);
	}

	private void writeFooter() throws IOException {
		if (repeatCount > 0) {
			saveOpRun();
		}

		// The stream's end marker (I have no idea why this exists)
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x00);
		stream.writeByte(0x01);

		if (debugging) {
			System.out.println("Statistics:");
			System.out.println("  RGBA : " + statRGBA);
			System.out.println("  RGB  : " + statRGB);
			System.out.println("  Run  : " + Arrays.toString(statRun));
			System.out.println("  Luma : " + statLuma);
			System.out.println("  Diff : " + statDiff);
			System.out.println("  Index: " + statIndex);
		}
	}

	private boolean checkUpdateAndAbort(int progressPosition, int progressInterval) {
		if (progressPosition >= nextUpdateAt) {
			nextUpdateAt += progressInterval;

			pixelsDone += width;
			processImageProgress(pixelsDone * 100.0f / totalPixels);

			// If write has been aborted, just return. processWriteAborted will be called later
			return abortRequested();
		}
		return false;
	}
}
