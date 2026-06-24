package com.conveyal.datatools.manager.models.transform;

import com.conveyal.datatools.common.status.MonitorableJob;
import com.conveyal.datatools.manager.models.TableTransformResult;
import com.conveyal.datatools.manager.utils.GtfsUtils;
import com.conveyal.gtfs.loader.Field;
import com.conveyal.gtfs.loader.Table;
import com.conveyal.gtfs.util.CsvReaderUtil;
import com.csvreader.CsvReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.supercsv.io.CsvListWriter;
import org.supercsv.prefs.CsvPreference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static com.conveyal.gtfs.loader.Field.getFieldIndex;

/**
 * The GTFS loader used by this application only parses time values with one or two hour digits. Some GTFS feeds contain
 * valid multi-day stop times with three or more hour digits. When those values are loaded, the loader emits a literal
 * "null" into the PostgreSQL COPY stream, which aborts the entire stop_times table load. Blank only those values so
 * stop_times and patterns can still be imported.
 */
public class NormalizeStopTimeFieldsTransformation extends ZipTransformation {
    private static final Logger LOG = LoggerFactory.getLogger(NormalizeStopTimeFieldsTransformation.class);
    private static final Pattern EXTENDED_HOUR_TIME = Pattern.compile("^(\\d{3,}):(\\d{2}):(\\d{2})$");
    private static final String STOP_TIMES_TABLE_NAME = "stop_times";
    private static final String STOP_TIMES_FILE_NAME = STOP_TIMES_TABLE_NAME + ".txt";
    private static final String ARRIVAL_TIME_FIELD_NAME = "arrival_time";
    private static final String DEPARTURE_TIME_FIELD_NAME = "departure_time";

    public NormalizeStopTimeFieldsTransformation() {
        table = STOP_TIMES_TABLE_NAME;
    }

    @Override
    public void validateParameters(MonitorableJob.Status status) {
        // Not required.
    }

    @Override
    public void transform(FeedTransformZipTarget zipTarget, MonitorableJob.Status status) {
        Path tempZipPath = null;
        boolean tempZipMovedToOriginal = false;

        try (
            StringWriter stringWriter = new StringWriter();
            CsvListWriter writer = new CsvListWriter(stringWriter, CsvPreference.STANDARD_PREFERENCE)
        ) {
            tempZipPath = Files.createTempFile("stop-time-field-normalization", ".zip");
            Path originalZipPath = Paths.get(zipTarget.gtfsFile.getAbsolutePath());
            Files.copy(originalZipPath, tempZipPath, StandardCopyOption.REPLACE_EXISTING);

            Table gtfsTable = GtfsUtils.getGtfsTable(STOP_TIMES_TABLE_NAME);
            int modifiedRowCount;
            try (ZipFile zipFile = new ZipFile(tempZipPath.toAbsolutePath().toString())) {
                CsvReader csvReader = CsvReaderUtil.getCsvReaderAccordingToFileName(gtfsTable, zipFile, null);
                if (csvReader == null) {
                    LOG.warn("Unable to normalize stop time fields because stop_times.txt was not found.");
                    return;
                }
                modifiedRowCount = generateCsvContent(writer, csvReader, gtfsTable);
            }
            if (modifiedRowCount == 0) {
                return;
            }

            writeCsvContent(zipTarget, tempZipPath, stringWriter, modifiedRowCount);
            LOG.info("Stop time field normalization successful, {} row(s) changed.", modifiedRowCount);

            Files.move(tempZipPath, originalZipPath, StandardCopyOption.REPLACE_EXISTING);
            tempZipMovedToOriginal = true;
            tempZipPath = null;
        } catch (ZipException ze) {
            status.fail(
                String.format("Stop time field normalization failed because the GTFS archive is corrupted (%s).", ze.getMessage()),
                ze
            );
        } catch (Exception e) {
            status.fail("Unknown error encountered while normalizing stop time fields", e);
        } finally {
            if (!tempZipMovedToOriginal && tempZipPath != null) {
                try {
                    Files.deleteIfExists(tempZipPath);
                } catch (IOException e) {
                    LOG.warn("Unable to delete temporary stop time normalization file {}", tempZipPath, e);
                }
            }
        }
    }

    static String normalizeLoaderIncompatibleTime(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = EXTENDED_HOUR_TIME.matcher(value);
        if (!matcher.matches()) {
            return value;
        }
        try {
            int hours = Integer.parseInt(matcher.group(1));
            int minutes = Integer.parseInt(matcher.group(2));
            int seconds = Integer.parseInt(matcher.group(3));
            if (minutes >= 60 || seconds >= 60) {
                return "";
            }
            return hours < 100
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : "";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /** Generates headers and content for stop_times.txt, returning the number of modified rows. */
    private int generateCsvContent(CsvListWriter writer, CsvReader csvReader, Table gtfsTable) throws IOException {
        int modifiedRowCount = 0;
        try {
            final String[] headers = csvReader.getHeaders();
            Field[] fieldsFoundInZip = gtfsTable.getFieldsFromFieldHeaders(headers, null);
            int arrivalTimeFieldIndex = getFieldIndex(fieldsFoundInZip, ARRIVAL_TIME_FIELD_NAME);
            int departureTimeFieldIndex = getFieldIndex(fieldsFoundInZip, DEPARTURE_TIME_FIELD_NAME);

            writer.write(headers);
            while (csvReader.readRecord()) {
                String[] csvValues = csvReader.getValues();
                boolean modified = normalizeTimeValue(csvValues, arrivalTimeFieldIndex);
                modified |= normalizeTimeValue(csvValues, departureTimeFieldIndex);
                writer.write(csvValues);
                if (modified) {
                    modifiedRowCount++;
                }
            }
            writer.flush();
        } finally {
            csvReader.close();
        }
        return modifiedRowCount;
    }

    private boolean normalizeTimeValue(String[] csvValues, int fieldIndex) {
        if (fieldIndex == -1) {
            return false;
        }
        String originalValue = csvValues[fieldIndex];
        String normalizedValue = normalizeLoaderIncompatibleTime(originalValue);
        csvValues[fieldIndex] = normalizedValue;
        return !Objects.equals(originalValue, normalizedValue);
    }

    /** Write CSV content into stop_times.txt in the zip file, replacing the existing file. */
    private void writeCsvContent(
        FeedTransformZipTarget zipTarget,
        Path tempZipPath,
        StringWriter stringWriter,
        int modifiedRowCount
    ) throws IOException {
        try (
            FileSystem targetZipFs = FileSystems.newFileSystem(tempZipPath, (ClassLoader) null);
            InputStream inputStream = new ByteArrayInputStream(stringWriter.toString().getBytes(StandardCharsets.UTF_8))
        ) {
            Path targetTxtFilePath = getTablePathInZip(STOP_TIMES_FILE_NAME, targetZipFs);
            Files.copy(inputStream, targetTxtFilePath, StandardCopyOption.REPLACE_EXISTING);
            zipTarget.feedTransformResult.tableTransformResults.add(
                new TableTransformResult(STOP_TIMES_FILE_NAME, 0, modifiedRowCount, 0)
            );
        }
    }
}
