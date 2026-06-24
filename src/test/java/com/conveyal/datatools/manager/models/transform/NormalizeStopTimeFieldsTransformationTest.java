package com.conveyal.datatools.manager.models.transform;

import com.conveyal.datatools.UnitTest;
import com.conveyal.datatools.common.status.MonitorableJob;
import com.csvreader.CsvReader;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NormalizeStopTimeFieldsTransformationTest extends UnitTest {
    @Test
    void blanksOnlyLoaderIncompatibleExtendedStopTimes() throws Exception {
        File zip = createGtfsZip(
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "t1,099:59:59,100:00:00,s1,1\n" +
                "t1,101:03:07,101:04:07,s2,2\n" +
                "t2,001:02:03,25:00:00,s3,1\n" +
                "t3,bad,123:99:00,s4,1\n" +
                "t4,9999999999999999999999:00:00,000:00:00,s5,1\n"
        );
        FeedTransformZipTarget zipTarget = new FeedTransformZipTarget(zip);
        MonitorableJob.Status status = new MonitorableJob.Status();

        new NormalizeStopTimeFieldsTransformation().doTransform(zipTarget, status);

        assertFalse(status.error);
        assertEquals(1, zipTarget.feedTransformResult.tableTransformResults.size());
        assertEquals(5, zipTarget.feedTransformResult.tableTransformResults.get(0).updatedCount);
        List<String[]> rows = readStopTimeRows(zip);
        assertEquals("99:59:59", rows.get(0)[1]);
        assertEquals("", rows.get(0)[2]);
        assertEquals("", rows.get(1)[1]);
        assertEquals("", rows.get(1)[2]);
        assertEquals("1:02:03", rows.get(2)[1]);
        assertEquals("25:00:00", rows.get(2)[2]);
        assertEquals("bad", rows.get(3)[1]);
        assertEquals("", rows.get(3)[2]);
        assertEquals("", rows.get(4)[1]);
        assertEquals("0:00:00", rows.get(4)[2]);
    }

    @Test
    void doesNotRewriteCompatibleStopTimes() throws Exception {
        File zip = createGtfsZip(
            "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "t1,9:59:59,10:00:00,s1,1\n" +
                "t1,99:59:59,99:59:59,s2,2\n"
        );
        FeedTransformZipTarget zipTarget = new FeedTransformZipTarget(zip);
        MonitorableJob.Status status = new MonitorableJob.Status();

        new NormalizeStopTimeFieldsTransformation().doTransform(zipTarget, status);

        assertFalse(status.error);
        assertEquals(0, zipTarget.feedTransformResult.tableTransformResults.size());
        List<String[]> rows = readStopTimeRows(zip);
        assertEquals("9:59:59", rows.get(0)[1]);
        assertEquals("10:00:00", rows.get(0)[2]);
        assertEquals("99:59:59", rows.get(1)[1]);
        assertEquals("99:59:59", rows.get(1)[2]);
    }

    private File createGtfsZip(String stopTimesCsv) throws IOException {
        File zip = File.createTempFile("stop-time-normalization", ".zip");
        zip.deleteOnExit();
        try (ZipOutputStream zipFile = new ZipOutputStream(new FileOutputStream(zip))) {
            zipFile.putNextEntry(new ZipEntry("stop_times.txt"));
            zipFile.write(stopTimesCsv.getBytes(StandardCharsets.UTF_8));
        }
        return zip;
    }

    private List<String[]> readStopTimeRows(File zip) throws IOException {
        try (ZipFile gtfsZipfile = new ZipFile(zip)) {
            ZipEntry entry = gtfsZipfile.getEntry("stop_times.txt");
            try (InputStream zipInputStream = gtfsZipfile.getInputStream(entry)) {
                CsvReader csvReader = new CsvReader(zipInputStream, ',', StandardCharsets.UTF_8);
                try {
                    csvReader.readHeaders();
                    List<String[]> rows = new ArrayList<>();
                    while (csvReader.readRecord()) {
                        rows.add(csvReader.getValues());
                    }
                    return rows;
                } finally {
                    csvReader.close();
                }
            }
        }
    }
}
