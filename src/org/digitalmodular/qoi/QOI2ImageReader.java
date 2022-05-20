package org.digitalmodular.qoi;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-14
@SuppressWarnings({"ConstantConditions", "ReturnOfNull"})
public class QOI2ImageReader extends ImageReader {
	private ImageInputStream stream = null;

	private boolean gotHeader  = false;
	private int     width      = 0;
	private int     height     = 0;
	private int     channels   = 0;
	private int     colorSpace = 0; // Currently unused

	// State for the progress reports
	/** Number of pixels to read */
	private int totalPixels  = 0;
	/** Number of pixels read */
	private int pixelsDone   = 0;
	/** Notify image observers once per this amount of work */
	private int nextUpdateAt = 0;

	private BufferedImage theImage = null;

	public QOI2ImageReader(ImageReaderSpi originatingProvider) {
		super(originatingProvider);
	}

	@Override
	public void setInput(Object input, boolean seekForwardOnly, boolean ignoreMetadata) {
		super.setInput(input, seekForwardOnly, ignoreMetadata);
		stream = (ImageInputStream)input; // Always works

		// Clear all values based on the previous stream contents
		resetStreamSettings();
	}

	@Override
	public int getNumImages(boolean allowSearch) {
		if (stream == null) {
			throw new IllegalStateException("No input source set!");
		} else if (seekForwardOnly && allowSearch) {
			throw new IllegalStateException("seekForwardOnly and allowSearch can't both be true!");
		}

		return 1;
	}

	@Override
	public int getWidth(int imageIndex) throws IIOException {
		if (imageIndex != 0) {
			throw new IndexOutOfBoundsException("imageIndex != 0!");
		}

		readHeader();

		return width;
	}

	@Override
	public int getHeight(int imageIndex) throws IIOException {
		if (imageIndex != 0) {
			throw new IndexOutOfBoundsException("imageIndex != 0!");
		}

		readHeader();

		return height;
	}

	@Override
	public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex) throws IOException {
		if (imageIndex != 0) {
			throw new IndexOutOfBoundsException("imageIndex != 0!");
		}

		readHeader();

		Collection<ImageTypeSpecifier> imageTypeSpecifiers = new ArrayList<>(1);

		if (channels == 3) {
			imageTypeSpecifiers.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR));
			imageTypeSpecifiers.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB));
		} else {
			imageTypeSpecifiers.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_4BYTE_ABGR));
			imageTypeSpecifiers.add(ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB));
		}

		return imageTypeSpecifiers.iterator();
	}

	@Override
	public IIOMetadata getStreamMetadata() {
		return null;
	}

	@Override
	public IIOMetadata getImageMetadata(int imageIndex) {
		return null;
	}

	@Override
	public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
		if (imageIndex != 0) {
			throw new IndexOutOfBoundsException("imageIndex != 0!");
		}

		//noinspection OverlyBroadCatchBlock
		try {
			clearAbortRequest();
			processImageStarted(0);
			if (abortRequested()) {
				processReadAborted();
			} else {
				readHeader();
				decodeImage();

				if (abortRequested()) {
					processReadAborted();
				} else {
					processImageComplete();
				}
			}
		} catch (IllegalArgumentException | IllegalStateException ex) {
			throw ex;
		} catch (IOException ex) {
			throw new IIOException("I/O error reading QOI image data", ex);
		} catch (Throwable ex) {
			throw new IIOException("Unexpected exception during read", ex);
		}

		return theImage;
	}

	private void readHeader() throws IIOException {
		if (gotHeader) {
			return;
		}

		if (stream == null) {
			throw new IllegalStateException("Input source not set!");
		}

		try {
			int magic = stream.readInt();

			if (magic != QOI2ImageWriter.QOI_MAGIC) { // "qoi2" in big-endian
				throw new IIOException("Bad QOIF signature (" + Integer.toString(magic, 16) + ')');
			}

			width = stream.readInt();
			height = stream.readInt();
			channels = stream.readByte() & 0xFF;
			colorSpace = stream.readByte() & 0xFF;

			stream.flushBefore(stream.getStreamPosition());

			if (width <= 0) {
				throw new IIOException("Image width <= 0!");
			} else if (height <= 0) {
				throw new IIOException("Image height <= 0!");
			} else if (channels != 3 && channels != 4) {
				throw new IIOException("'channels' must be 3 or 4!");
			} else if (colorSpace > 1) {
				throw new IIOException("'colorSpace' 0 or 1!");
			}

			if ((long)width * height > Integer.MAX_VALUE - 2) {
				// We are not able to properly decode image that has number
				// of pixels greater than Integer.MAX_VALUE - 2
				throw new IIOException("Image of the size " + width + " by " + height + " has too many pixels");
			}

			gotHeader = true;
		} catch (IOException ex) {
			throw new IIOException("I/O error reading QOI header!", ex);
		}
	}

	private void decodeImage() throws IOException {
		// Construct a suitable target image
		theImage = getDestination(null, getImageTypes(0), width, height);

		checkReadParamBandSettings(null, channels, theImage.getSampleModel().getNumBands());

		// Prepare progress notification variables
		totalPixels = width * height;
		pixelsDone = 0;
		nextUpdateAt = 0;

		int lineStride   = width;
		int totalSamples = totalPixels;

		WritableRaster raster = theImage.getWritableTile(0, 0);

		byte[] bytePixels = null;
		int[]  intPixels  = null;

		DataBuffer dataBuffer = raster.getDataBuffer();
		if (dataBuffer.getDataType() == DataBuffer.TYPE_BYTE) {
			bytePixels = ((DataBufferByte)dataBuffer).getData();
			lineStride *= channels;
			totalSamples *= channels;
		} else {
			intPixels = ((DataBufferInt)dataBuffer).getData();
		}

		byte     r                 = 0;
		byte     g                 = 0;
		byte     b                 = 0;
		byte     a                 = (byte)255;
		int      repeatMultiplier  = 1;
		byte[][] recentColorsList  = new byte[64][4];
		int      recentColorsIndex = 0;

		processPassStarted(theImage, 0, 0, 0, 0, 0, 1, 1, null);

		int p = 0;
		while (p < totalSamples) {
			if (checkUpdateAndAbort(p, lineStride)) {
				break;
			}

			int     repeatCount           = 1;
			boolean resetRepeatMultiplier = true;
			boolean recordRecent          = true;

			int code = stream.read();
			if (code < 0) {
				break; // EOF reached
			} else if (code == QOI2ImageWriter.QOI_OP_RGBA) {
				r = (byte)stream.read();
				g = (byte)stream.read();
				b = (byte)stream.read();
				a = (byte)stream.read();
			} else if (code == QOI2ImageWriter.QOI_OP_RGB) {
				r = (byte)stream.read();
				g = (byte)stream.read();
				b = (byte)stream.read();
			} else if (code == QOI2ImageWriter.QOI_OP_A) {
				a = (byte)stream.read();
			} else if (code == QOI2ImageWriter.QOI_OP_DELTA) {
				code = stream.read();
				r += (code >> 4 & 0b00001111) - 8;
				g += (code & 0b00001111) - 8;
				code = stream.read();
				b += (code >> 4 & 0b00001111) - 8;
				a += (code & 0b00001111) - 8;
			} else {
				int op2 = code & 0b11000000;

				if (op2 == QOI2ImageWriter.QOI_OP_INDEX) {
					code &= 0b00111111;
					byte[] c = recentColorsList[code];
					r = c[0];
					g = c[1];
					b = c[2];
					a = c[3];
					recordRecent = false;
				} else if (op2 == QOI2ImageWriter.QOI_OP_DIFF) {
					r += (code >> 4 & 0b00000011) - 2;
					g += (code >> 2 & 0b00000011) - 2;
					b += (code & 0b00000011) - 2;
				} else if (op2 == QOI2ImageWriter.QOI_OP_LUMA) {
					int dg = (code & 0b00111111) - 32;
					g += dg;
					code = stream.read();
					r += dg + (code >> 4 & 0b00001111) - 8;
					b += dg + (code & 0b00001111) - 8;
				} else /*if (op2 == QOIImageWriter.QOI_OP_RUN)*/ {
					repeatCount = ((code & 0b00111111) + 1) * repeatMultiplier;

					recordRecent = p == 0;
					repeatMultiplier *= 60;
					resetRepeatMultiplier = false;
				}
			}

			if (resetRepeatMultiplier) {
				repeatMultiplier = 1;
			}

			if (recordRecent) {
				recentColorsList[recentColorsIndex][0] = r;
				recentColorsList[recentColorsIndex][1] = g;
				recentColorsList[recentColorsIndex][2] = b;
				recentColorsList[recentColorsIndex][3] = a;
				recentColorsIndex = (recentColorsIndex + 1) & 63;
			}

			if (bytePixels != null) {
				do {
					if (channels == 4) {
						if (p + 3 >= totalSamples) {
							break;
						}

						bytePixels[p++] = a;
					} else if (p + 2 >= totalSamples) {
						break;
					}
					bytePixels[p++] = b;
					bytePixels[p++] = g;
					bytePixels[p++] = r;

					repeatCount--;
				} while (repeatCount > 0);
			} else if (intPixels != null) {
				int argb = (((a & 0xFF) << 8 |
				             (r & 0xFF)) << 8 |
				            (g & 0xFF)) << 8 |
				           (b & 0xFF);
				do {
					intPixels[p++] = argb;

					repeatCount--;
				} while (repeatCount > 0);
			}
		}

		processPassComplete(theImage);
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

	@Override
	public void reset() {
		super.reset();
		resetStreamSettings();
	}

	private void resetStreamSettings() {
		gotHeader = false;
		width = 0;
		height = 0;
		channels = 0;
		colorSpace = 0;
	}
}
