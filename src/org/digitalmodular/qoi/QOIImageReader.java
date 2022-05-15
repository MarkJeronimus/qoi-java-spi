package org.digitalmodular.qoi;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.DataInputStream;
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

import org.jetbrains.annotations.Nullable;

/**
 * @author Zom-B
 */
// Created 2022-05-14
public class QOIImageReader extends ImageReader {
	private static final int[][] BAND_OFFSETS = {
			{0, 1, 2},   // RGB in RGB order
			{0, 1, 2, 3} // RGBA in RGBA order
	};

	private static final int QOI_OP_RGB   = 0b11111110;
	private static final int QOI_OP_RGBA  = 0b11111111;
	private static final int QOI_OP_INDEX = 0b00_000000; // Only upper 2 bits used
	private static final int QOI_OP_DIFF  = 0b01_000000; // Only upper 2 bits used
	private static final int QOI_OP_LUMA  = 0b10_000000; // Only upper 2 bits used
	private static final int QOI_OP_RUN   = 0b11_000000; // Only upper 2 bits used

	@SuppressWarnings("CharUsedInArithmeticContext")
	static final int QOI_MAGIC = (('q' << 8 | 'o') << 8 | 'i') << 8 | 'f'; // "qoif", big-endian

	private ImageInputStream stream = null;

	private boolean gotHeader  = false;
	private int     width      = 0;
	private int     height     = 0;
	private int     channels   = 0;
	private int     colorSpace = 0;

	private @Nullable DataInputStream pixelStream = null;

	private Rectangle sourceRegion = null;

	// The number of source pixels processed
	int pixelsDone = 0;

	// The total number of pixels in the source image
	int totalPixels;

	private BufferedImage theImage = null;

	public QOIImageReader(ImageReaderSpi originatingProvider) {
		super(originatingProvider);
	}

	@Override
	public void setInput(Object input,
	                     boolean seekForwardOnly,
	                     boolean ignoreMetadata) {
		super.setInput(input, seekForwardOnly, ignoreMetadata);
		stream = (ImageInputStream)input; // Always works

		// Clear all values based on the previous stream contents
		resetStreamSettings();
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

			if (magic != QOI_MAGIC) { // "qoif" in big-endian
				throw new IIOException("Bad QOIF signature (" + Integer.toString(magic, 16) + ')');
			}

			width = stream.readInt();
			height = stream.readInt();
			channels = stream.readByte() & 0xFF;
			colorSpace = stream.readByte() & 0xFF;
			System.out.println("width     : " + width);
			System.out.println("height    : " + height);
			System.out.println("channels  : " + channels);
			System.out.println("colorSpace: " + colorSpace);

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

	@Override
	public int getNumImages(boolean allowSearch) throws IOException {
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
	public @Nullable IIOMetadata getStreamMetadata() {
		System.out.println("getStreamMetadata call");
		return null;
	}

	@Override
	public @Nullable IIOMetadata getImageMetadata(int imageIndex) {
		System.out.println("getImageMetadata call");
		return null;
	}

	@Override
	public BufferedImage read(int imageIndex, ImageReadParam param) throws IOException {
		if (imageIndex != 0) {
			throw new IndexOutOfBoundsException("imageIndex != 0!");
		}

		try {
			readImage();
		} catch (IOException | IllegalStateException | IllegalArgumentException ex) {
			throw ex;
		} catch (Throwable ex) {
			throw new IIOException("Unexpected exception during read: ", ex);
		}

		return theImage;
	}

	private void readImage() throws IIOException {
		try {
			/*
			 * Here we may fail to allocate a buffer for destination
			 * image due to memory limitation.
			 *
			 * If the read operation triggers OutOfMemoryError, the same
			 * will be wrapped in an IIOException at QOIImageReader.read
			 * method.
			 *
			 * The recovery strategy for this case should be defined at
			 * the application level, so we will not try to estimate
			 * the required amount of the memory and/or handle OOM in
			 * any other way.
			 */
			theImage = getDestination(null, getImageTypes(0), width, height);

			sourceRegion = new Rectangle(0, 0, width, height);

			checkReadParamBandSettings(null, channels, theImage.getSampleModel().getNumBands());

			clearAbortRequest();
			processImageStarted(0);
			if (abortRequested()) {
				processReadAborted();
			} else {
				decodeImage();
				if (abortRequested()) {
					processReadAborted();
				} else {
					processImageComplete();
				}
			}
		} catch (IOException ex) {
			throw new IIOException("Error reading QOI image data", ex);
		}
	}

	private void decodeImage() throws IOException {
		pixelsDone = 0;
		totalPixels = width * height;
		int totalBytes = totalPixels;

		WritableRaster raster = theImage.getWritableTile(0, 0);

		byte[] bytePixels = null;
		int[]  intPixels  = null;
		int    lineStride;

		DataBuffer dataBuffer = raster.getDataBuffer();
		if (dataBuffer.getDataType() == DataBuffer.TYPE_BYTE) {
			bytePixels = ((DataBufferByte)dataBuffer).getData();
			lineStride = width * channels;
			totalBytes *= channels;
		} else {
			intPixels = ((DataBufferInt)dataBuffer).getData();
			lineStride = width;
		}

		byte     r              = 0;
		byte     g              = 0;
		byte     b              = 0;
		byte     a              = (byte)255;
		byte[][] colorHashTable = new byte[64][4];

		processPassStarted(theImage, 0, 0, 0, 0, 0, 1, 1, null);

		// Notify image observers once per row (without the notifications, we wouldn't need a separate loop for x and y)
		int nextUpdate = lineStride;
		updateImageProgress(0);

		int p = 0;
		while (p < totalBytes) {

			// If read has been aborted, just return. processReadAborted will be called later
			if (abortRequested()) {
				return;
			}

			int     runLength  = 1;
			boolean recordHash = true;

			int code = stream.read();
			if (code == QOI_OP_RGB) {
				r = (byte)stream.read();
				g = (byte)stream.read();
				b = (byte)stream.read();
			} else if (code == QOI_OP_RGBA) {
				r = (byte)stream.read();
				g = (byte)stream.read();
				b = (byte)stream.read();
				a = (byte)stream.read();
			} else {
				int op2 = code & 0b11000000;

				if (op2 == QOI_OP_INDEX) {
					code &= 0b00111111;
					byte[] c = colorHashTable[code];
					r = c[0];
					g = c[1];
					b = c[2];
					a = c[3];
					recordHash = false;
				} else if (op2 == QOI_OP_DIFF) {
					r += (code >> 4 & 0b00000011) - 2;
					g += (code >> 2 & 0b00000011) - 2;
					b += (code & 0b00000011) - 2;
				} else if (op2 == QOI_OP_LUMA) {
					int dg = (code & 0b00111111) - 32;
					g += dg;
					code = stream.read();
					r += dg + (code >> 4 & 0b00001111) - 8;
					b += dg + (code & 0b00001111) - 8;
				} else {//if (op2 == QOI_OP_RUN) {
					runLength = (code & 0b00111111) + 1;
					recordHash = false;
				}
			}

			if (recordHash) {
				int hash = (r * 3 + g * 5 + b * 7 + a * 11) & 63;
				colorHashTable[hash][0] = r;
				colorHashTable[hash][1] = g;
				colorHashTable[hash][2] = b;
				colorHashTable[hash][3] = a;
			}

			if (bytePixels != null) {
				do {
					if (channels == 4) {
						bytePixels[p++] = a;
					}
					bytePixels[p++] = b;
					bytePixels[p++] = g;
					bytePixels[p++] = r;

					runLength--;
				} while (runLength > 0);
			} else if (intPixels != null) {
				int argb = (((a & 0xFF) << 8 |
				             (r & 0xFF)) << 8 |
				            (g & 0xFF)) << 8 |
				           (b & 0xFF);
				do {
					intPixels[p++] = argb;

					runLength--;
				} while (runLength > 0);
			}

			if (p >= nextUpdate) {
				nextUpdate += lineStride;
				updateImageProgress(width);
			}
		}

		processPassComplete(theImage);
	}

	private void updateImageProgress(int newPixels) {
		pixelsDone += newPixels;
		processImageProgress(100.0F * pixelsDone / totalPixels);
	}

	private WritableRaster createRaster(int width, int height, int channels, int scanlineStride) {

		DataBuffer dataBuffer;
		Point      origin = new Point(0, 0);
		dataBuffer = new DataBufferByte(height * scanlineStride);

		return Raster.createInterleavedRaster(dataBuffer,
		                                      width, height,
		                                      scanlineStride,
		                                      channels,
		                                      BAND_OFFSETS[channels - 3],
		                                      origin);
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
		pixelStream = null;
	}
}
