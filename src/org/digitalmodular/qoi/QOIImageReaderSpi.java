package org.digitalmodular.qoi;

import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;

/**
 * @author Zom-B
 */
// Created 2022-05-14
public class QOIImageReaderSpi extends ImageReaderSpi {
	private static final String vendorName = "phoboslab";

	private static final String version = "1.0";

	private static final String[] names = {"qoi", "QOI"};

	private static final String[] suffixes = {"qoi"};

	private static final String[] MIMETypes = {"image/qoi", "image/x-qoi"};

	private static final String readerClassName = "com.sun.imageio.plugins.qoi.QOIImageReader";

	private static final String[] writerSpiNames = {"com.sun.imageio.plugins.qoi.QOIImageWriterSpi"};

	public QOIImageReaderSpi() {
		super(vendorName,
		      version,
		      names,
		      suffixes,
		      MIMETypes,
		      readerClassName,
		      new Class<?>[]{ImageInputStream.class},
		      writerSpiNames,
		      false,
		      null, null,
		      null, null,
		      true,
		      null,
		      null,
		      null, null
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
