package org.digitalmodular.qoi;

import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.util.Locale;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-16
public class QOIImageWriterSpi extends ImageWriterSpi {
	private static final String   VENDOR_NAME       = "phoboslab";
	private static final String   VERSION           = "1.0";
	private static final String[] FORMAT_NAMES      = {"qoi", "QOI"};
	private static final String[] SUFFIXES          = {"qoi"};
	private static final String[] MIME_TYPES        = {"image/qoi", "image/x-qoi"};
	private static final String   WRITER_CLASS_NAME = "org.digitalmodular.qoi.QOIImageWriter";
	private static final String[] READER_SPI_NAMES  = {"org.digitalmodular.qoi.QOIImageReaderSpi"};

	public QOIImageWriterSpi() {
		super(VENDOR_NAME,
		      VERSION,
		      FORMAT_NAMES,
		      SUFFIXES,
		      MIME_TYPES,
		      WRITER_CLASS_NAME,
		      new Class<?>[]{ImageOutputStream.class},
		      READER_SPI_NAMES,
		      false,
		      null,
		      null,
		      null,
		      null,
		      false,
		      null,
		      null,
		      null,
		      null
		);
	}

	@Override
	public boolean canEncodeImage(ImageTypeSpecifier type) {
		ColorModel colorModel = type.getColorModel();

		// Shortcut: If palletted, skip checking channels (always 1) and alpha (don't care)
		if (colorModel instanceof IndexColorModel) {
			return true;
		}

		int[] sampleSizes = type.getSampleModel().getSampleSize();
		int   channels    = sampleSizes.length;

		// Sample sizes must all be between 1 and 8 and be a power of two
		for (int sampleSize : sampleSizes) {
			if (sampleSize < 1 || sampleSize > 8 && Integer.bitCount(sampleSize) == 1) {
				return false;
			}
		}

		// These combinations of channels and alpha are valid
		if (colorModel.hasAlpha()) {
			return channels == 2 || channels == 4;
		} else {
			return channels == 1 || channels == 3;
		}
	}

	@Override
	public String getDescription(Locale locale) {
		return "Quite OK Image Format writer";
	}

	@Override
	public ImageWriter createWriterInstance(Object extension) {
		return new QOIImageWriter(this);
	}
}
