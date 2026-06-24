# Stop Time Field Normalization

## Context

Data Tools loads GTFS zips through the `gtfs-lib` JDBC loader before editor patterns are available. The loader stores `stop_times.arrival_time` and `stop_times.departure_time` as integer seconds in Postgres.

The current loader only parses time strings with one or two hour digits, such as `1:02:03` or `25:00:00`. Some real GTFS feeds contain longer multi-day times such as `101:03:07`.

## Failure Mode

When `gtfs-lib` sees a three-or-more-digit hour value in `stop_times.txt`, its time parser returns an invalid result with no cleaned value. That becomes the literal string `null` in the PostgreSQL `COPY` stream instead of SQL null.

Postgres then rejects the row with an integer parse error, and the entire `stop_times` table load aborts. Routes and trips can still load, but pattern generation has no stop-time rows to group, so the editor shows zero trip patterns.

This was seen on the Safiri Intercity feed. Route `SARGN429:SER429` had outbound and inbound trips in `trips.txt`, but the editor showed zero patterns because `stop_times` loaded with zero rows.

## Implementation

`ProcessSingleFeedJob` now runs `NormalizeStopTimeFieldsTransformation` as the final pre-load zip transformation, immediately before `LoadFeedJob`.

The transform rewrites only `stop_times.txt` and only the `arrival_time` and `departure_time` fields that this loader cannot safely import:

- Compatible values, such as `1:02:03`, `25:00:00`, and `99:59:59`, are left unchanged.
- Zero-padded extended values under 100 hours, such as `001:02:03` or `099:59:59`, are normalized to loader-compatible values.
- Values with hours of 100 or higher, such as `100:00:00` or `101:03:07`, are blanked so they load as SQL null instead of aborting the whole table.
- Malformed extended-hour values are also blanked to keep the import from failing fatally.

Blanking the unrepresentable values loses those individual time values, but it preserves the stop-time rows, stop sequences, pattern stops, and route pattern generation. That is better for the editor than losing the entire `stop_times` table.

## Scope

This is a compatibility shim for the existing loader. If the underlying `gtfs-lib` time parser is upgraded to support three-or-more-digit hour values and write true nulls to PostgreSQL COPY, this transform should be revisited or removed.

The transform is not exposed as a user-configurable feed transformation. It is a built-in pre-load guard.

## Tests

Focused coverage lives in `NormalizeStopTimeFieldsTransformationTest` and verifies:

- Loader-compatible times are not rewritten.
- Extended-hour values that would abort the loader are rewritten.
- Zero-padded values under 100 hours are preserved in parseable form.
- Malformed extended-hour values become blanks instead of fatal loader input.
