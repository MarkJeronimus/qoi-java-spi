package org.digitalmodular.qoi;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * @author Mark Jeronimus
 */
// Created 2022-05-14
public class QOIImageReaderSpi extends ImageReaderSpi {
	private static final String   VENDOR_NAME       = "phoboslab";
	private static final String   VERSION           = "1.0";
	private static final String[] FORMAT_NAMES      = {"qoi", "QOI"};
	private static final String[] SUFFIXES          = {"qoi"};
	private static final String[] MIME_TYPES        = {"image/qoi", "image/x-qoi"};
	private static final String   READER_CLASS_NAME = "org.digitalmodular.qoi.QOIImageReader";
	private static final String[] WRITER_SPI_NAMES  = {"org.digitalmodular.qoi.QOIImageWriterSpi"};

	public QOIImageReaderSpi() {
		super(VENDOR_NAME,
		      VERSION,
		      FORMAT_NAMES,
		      SUFFIXES,
		      MIME_TYPES,
		      READER_CLASS_NAME,
		      new Class<?>[]{ImageInputStream.class},
		      WRITER_SPI_NAMES,
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
	public String getDescription(Locale locale) {
		return "Quite OK Image Format reader";
	}

	@Override
	public boolean canDecodeInput(Object input) throws IOException {
		if (!(input instanceof ImageInputStream)) {
			return false;
		}

		ImageInputStream stream = (ImageInputStream)input;
		stream.mark();
		int magic = stream.readInt();
		stream.reset();

		return magic == QOIImageReader.QOI_MAGIC;
	}

	@Override
	public ImageReader createReaderInstance(Object extension) throws IOException {
		return new QOIImageReader(this);
	}
}
