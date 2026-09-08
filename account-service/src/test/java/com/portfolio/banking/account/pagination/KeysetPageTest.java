package com.portfolio.banking.account.pagination;

import com.portfolio.banking.account.dto.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The paging logic itself, tested without a database.
 * <p>
 * Everything here is off-by-one territory - the probe row, whether the cursor
 * points at the last returned row or the one after it, whether a full final
 * page hands out a cursor that leads nowhere. Those are the mistakes that
 * produce a client loop which repeats rows, skips them, or never terminates,
 * and none of them are visible in a test with fewer rows than a page.
 */
class KeysetPageTest {

    private record Row(UUID id, Instant createdAt) {
        static Row at(String instant) {
            return new Row(UUID.randomUUID(), Instant.parse(instant));
        }
    }

    private static PageResponse<Row> pageOf(KeysetPage page, List<Row> fetched) {
        return page.build(fetched, row -> row, Row::createdAt, Row::id);
    }

    @Test
    void of_withNoCursor_startsAtTheFirstPage() {
        KeysetPage page = KeysetPage.of(null, null);

        assertThat(page.isFirstPage()).isTrue();
        assertThat(page.afterCreatedAt()).isNull();
        assertThat(page.afterId()).isNull();
        assertThat(page.limit()).isEqualTo(KeysetPage.DEFAULT_LIMIT);
    }

    @Test
    void of_withBlankCursor_isTreatedAsNoCursorRatherThanAsMalformed() {
        assertThat(KeysetPage.of("   ", null).isFirstPage()).isTrue();
    }

    @Test
    void of_clampsAnOversizedLimitInsteadOfRejectingIt() {
        assertThat(KeysetPage.of(null, 5_000).limit()).isEqualTo(KeysetPage.MAX_LIMIT);
    }

    @Test
    void of_rejectsALimitBelowOne() {
        // Zero would produce a fetch of exactly one row, all of which is the
        // probe - an empty page with a cursor, forever.
        assertThatThrownBy(() -> KeysetPage.of(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 1");
    }

    @Test
    void of_rejectsACursorThatIsNotEvenBase64() {
        assertThatThrownBy(() -> KeysetPage.of("not a cursor!!", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed cursor");
    }

    @Test
    void of_rejectsWellFormedBase64ThatDecodesToSomethingElse() {
        String base64OfGarbage = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello there".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> KeysetPage.of(base64OfGarbage, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed cursor");
    }

    @Test
    void build_dropsTheProbeRowAndEmitsACursorForTheLastRowItActuallyReturned() {
        KeysetPage page = KeysetPage.of(null, 3);
        assertThat(page.fetchSize()).isEqualTo(4);

        Row third = Row.at("2026-01-01T00:00:03Z");
        Row probe = Row.at("2026-01-01T00:00:04Z");
        List<Row> fetched = List.of(
                Row.at("2026-01-01T00:00:01Z"), Row.at("2026-01-01T00:00:02Z"), third, probe);

        PageResponse<Row> result = pageOf(page, fetched);

        assertThat(result.items()).hasSize(3).doesNotContain(probe);
        // The cursor must point at the last row the client received, not at
        // the probe - otherwise the probe row is skipped on the next page.
        KeysetPage next = KeysetPage.of(result.nextCursor(), 3);
        assertThat(next.afterId()).isEqualTo(third.id());
        assertThat(next.afterCreatedAt()).isEqualTo(third.createdAt());
    }

    @Test
    void build_whenTheFetchExactlyFillsThePage_reportsNoNextPage() {
        // The case the probe row exists for. Without it, a final page that
        // happens to be exactly full is indistinguishable from a page with
        // more behind it, and the client is handed a cursor to an empty page.
        KeysetPage page = KeysetPage.of(null, 3);
        List<Row> fetched = List.of(
                Row.at("2026-01-01T00:00:01Z"), Row.at("2026-01-01T00:00:02Z"), Row.at("2026-01-01T00:00:03Z"));

        PageResponse<Row> result = pageOf(page, fetched);

        assertThat(result.items()).hasSize(3);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void build_withNothingAtAll_isAnEmptyLastPage() {
        PageResponse<Row> result = pageOf(KeysetPage.of(null, 10), List.of());

        assertThat(result.items()).isEmpty();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void cursor_survivesMicrosecondPrecision() {
        // Postgres stores timestamptz to microseconds. A cursor that lost
        // those digits would point just before the row it names, and that row
        // would be returned a second time on the next page.
        Row row = Row.at("2026-01-01T00:00:00.123456Z");
        PageResponse<Row> result = pageOf(KeysetPage.of(null, 1), List.of(row, Row.at("2026-01-01T00:00:00Z")));

        KeysetPage next = KeysetPage.of(result.nextCursor(), 1);

        assertThat(next.afterCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00.123456Z"));
    }

    @Test
    void cursor_isUrlSafeSoItNeedsNoEscapingInAQueryString() {
        PageResponse<Row> result = pageOf(KeysetPage.of(null, 1),
                List.of(Row.at("2026-01-01T00:00:02Z"), Row.at("2026-01-01T00:00:01Z")));

        assertThat(result.nextCursor()).matches("[A-Za-z0-9_-]+");
    }

    /**
     * The property that actually matters to a client: walking the cursor from
     * the start visits every row exactly once and then stops. Asserted over a
     * page size that does not divide the row count, since an exact multiple
     * would hide a cursor that leads to a trailing empty page.
     */
    @Test
    void walkingEveryPage_visitsEachRowExactlyOnceAndTerminates() {
        List<Row> all = IntStream.range(0, 17)
                .mapToObj(i -> new Row(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)))
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))  // newest first, as the query returns them
                .toList();

        List<UUID> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;

        do {
            KeysetPage page = KeysetPage.of(cursor, 5);
            List<Row> remaining = page.isFirstPage()
                    ? all
                    : all.stream().dropWhile(row -> !row.id().equals(page.afterId())).skip(1).toList();

            PageResponse<Row> result = pageOf(page, remaining.stream().limit(page.fetchSize()).toList());
            result.items().forEach(row -> seen.add(row.id()));
            cursor = result.nextCursor();
            pages++;
        } while (cursor != null && pages < 100);

        assertThat(pages).as("17 rows at 5 per page is 5+5+5+2").isEqualTo(4);
        assertThat(seen).hasSize(17).doesNotHaveDuplicates()
                .containsExactlyElementsOf(all.stream().map(Row::id).toList());
    }
}
