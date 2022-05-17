package org.digitalmodular.qoi;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.DirectColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.io.IOException;
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
@SuppressWarnings({"ConstantConditions", "ReturnOfNull"})
public final class QOIImageWriter extends ImageWriter {
	@SuppressWarnings("CharUsedInArithmeticContext")
	static final int QOI_MAGIC = (('q' << 8 | 'o') << 8 | 'i') << 8 | 'f'; // "qoif", big-endian

	private ImageOutputStream stream = null;

	private int width      = 0;
	private int height     = 0;
	private int channels   = 0;
	private int colorSpace = 0; // Currently unused

	// State for the progress reports
	/** Number of pixels to read */
	private int totalPixels  = 0;
	/** Number of pixels read */
	private int pixelsDone   = 0;
	/** Notify image observers once per this amount of work */
	private int nextUpdateAt = 0;

	public QOIImageWriter(ImageWriterSpi originatingProvider) {
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

				int pixel = pixels[i];
				int r     = (pixel >> bitOffsets[0]) & 0xFF;
				int g     = (pixel >> bitOffsets[1]) & 0xFF;
				int b     = (pixel >> bitOffsets[2]) & 0xFF;
				encodeColor(r, g, b, 255);
			}
		} else if (srcChannels == 4 && channels == 4) {
			for (int i = 0; i < pixels.length; i++) {
				if (checkUpdateAndAbort(i, lineStride)) {
					break;
				}

				int pixel = pixels[i];
				int r     = (pixel >> bitOffsets[0]) & 0xFF;
				int g     = (pixel >> bitOffsets[1]) & 0xFF;
				int b     = (pixel >> bitOffsets[2]) & 0xFF;
				int a     = (pixel >> bitOffsets[3]) & 0xFF;
				encodeColor(r, g, b, a);
			}
		} else if (channels == 4) {
			throw new UnsupportedOperationException(
					"Cannot encode image with DirectColorModel, " + srcChannels + " channels, and alpha");
		} else {
			throw new UnsupportedOperationException(
					"Cannot encode image with DirectColorModel and " + srcChannels + " channels");
		}
	}

	@SuppressWarnings("ValueOfIncrementOrDecrementUsed")
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

				int sample = samples[p++];
				encodeColor(sample, sample, sample, 255);
			}
		} else if (srcChannels == 2 && channels == 4) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				int y = samples[p + bandOffsets[0]];
				int a = samples[p + bandOffsets[1]];
				p += 2;
				encodeColor(y, y, y, a);
			}
		} else if (srcChannels == 3 && channels == 3) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				int r = samples[p + bandOffsets[0]];
				int g = samples[p + bandOffsets[1]];
				int b = samples[p + bandOffsets[2]];
				p += 3;
				encodeColor(r, g, b, 255);
			}
		} else if (srcChannels == 4 && channels == 4) {
			while (p < samples.length) {
				if (checkUpdateAndAbort(p, lineStride)) {
					break;
				}

				int r = samples[p + bandOffsets[0]];
				int g = samples[p + bandOffsets[1]];
				int b = samples[p + bandOffsets[2]];
				int a = samples[p + bandOffsets[3]];
				p += 4;
				encodeColor(r, g, b, a);
			}
		} else if (channels == 4) {
			throw new UnsupportedOperationException(
					"Cannot encode image with ComponentColorModel, " + srcChannels + " channels, and alpha");
		} else {
			throw new UnsupportedOperationException(
					"Cannot encode image with ComponentColorModel and " + srcChannels + " channels");
		}
	}

	private void encodeColor(int r, int g, int b, int a) throws IOException {
		//TODO
		stream.writeByte(r);
		stream.writeByte(g);
		stream.writeByte(b);
		stream.writeByte(a);
	}

	private boolean checkUpdateAndAbort(int progressPosition, int progressInterval) {
		if (progressPosition >= nextUpdateAt) {
			nextUpdateAt += progressInterval;

			pixelsDone += width;
			processImageProgress(pixelsDone * 100.0f / totalPixels);

			// If read has been aborted, just return. processReadAborted will be called later
			return abortRequested();
		}
		return false;
	}
}
