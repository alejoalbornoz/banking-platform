package com.portfolio.banking.account.pagination;

import com.portfolio.banking.account.dto.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where a paginated read resumes from, and how many rows it may take.
 * <p>
 * <b>Keyset, not offset.</b> Every list this paginates is append-only and
 * sorted newest-first, so rows keep arriving at the front while a client reads
 * it. Under {@code OFFSET 20} that shifts the whole window: a row inserted
 * between two requests pushes one the client already saw down into the next
 * page, so it comes back twice - and had a row been removed instead, one would
 * be skipped entirely. A keyset asks for what comes after <em>one specific
 * row</em> rather than for whatever is left after skipping 20, and no
 * concurrent insert can change what that means. Offset also degrades as it
 * grows, since the database still walks and discards every skipped row; a
 * keyset is one index seek at any depth.
 * <p>
 * <b>The sort key is {@code (created_at, id)}, not {@code created_at} alone.</b>
 * Two rows can share a timestamp - two ledger entries posted in the same
 * microsecond is not a hypothetical - and a cursor pointing at a position that
 * several rows occupy cannot say which of them was already returned. The id
 * breaks the tie, which is why the supporting indexes carry both columns.
 * <p>
 * The timestamp inside a cursor always comes from a row the <em>database</em>
 * returned, never from an {@code Instant.now()} here. Postgres stores
 * {@code timestamptz} to microsecond precision while a Java {@code Instant}
 * carries nanoseconds, so a locally-built timestamp would compare unequal to
 * the stored value it was meant to point at, and the tiebreak above would
 * quietly stop working.
 */
public record KeysetPage(Instant afterCreatedAt, UUID afterId, int limit) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private static final char SEPARATOR = '|';

    /**
     * @param cursor {@code nextCursor} from a previous page, or null/blank to
     *                start from the newest row
     * @param limit  requested page size, or null for {@link #DEFAULT_LIMIT}.
     *                Clamped to {@link #MAX_LIMIT} rather than rejected: a
     *                caller asking for more than we serve gets the most we
     *                will serve, which is more useful than an error, and the
     *                cap is what stops {@code ?limit=1000000} from being an
     *                unbounded read wearing a page's clothes.
     * @throws IllegalArgumentException if the cursor is malformed or the limit
     *         is below 1 - both surface as a 400 through
     *         {@code GlobalExceptionHandler}
     */
    public static KeysetPage of(String cursor, Integer limit) {
        int effectiveLimit = clamp(limit);
        if (cursor == null || cursor.isBlank()) {
            return new KeysetPage(null, null, effectiveLimit);
        }
        Position position = decode(cursor);
        return new KeysetPage(position.createdAt(), position.id(), effectiveLimit);
    }

    public boolean isFirstPage() {
        return afterCreatedAt == null;
    }

    /**
     * How many rows to actually ask the database for: one more than the page
     * size. That extra row is never returned - it exists only to answer
     * whether anything follows this page, without a second COUNT query over
     * the table and without the classic bug of handing back a
     * {@code nextCursor} that leads to an empty page.
     */
    public int fetchSize() {
        return limit + 1;
    }

    /**
     * Spring Data's way of expressing a row limit on a repository method.
     * <p>
     * The offset is always zero, and that is the entire point: the cursor
     * already decided where this page starts, so there is never anything to
     * skip. {@code Pageable} appears here only because it is how a repository
     * method says LIMIT - none of the rest of it is in use.
     */
    public Pageable limitOnly() {
        return PageRequest.of(0, fetchSize());
    }

    /**
     * Turns the {@link #fetchSize()} rows a repository returned into a page:
     * drops the probe row, and derives the next cursor from the last row that
     * is actually being returned.
     */
    public <E, R> PageResponse<R> build(List<E> fetched,
                                         Function<E, R> toResponse,
                                         Function<E, Instant> createdAtOf,
                                         Function<E, UUID> idOf) {
        boolean hasMore = fetched.size() > limit;
        List<E> rows = hasMore ? fetched.subList(0, limit) : fetched;

        String nextCursor = null;
        if (hasMore) {
            E last = rows.get(rows.size() - 1);
            nextCursor = encode(createdAtOf.apply(last), idOf.apply(last));
        }
        return new PageResponse<>(rows.stream().map(toResponse).toList(), nextCursor);
    }

    /**
     * Base64url, so the token crosses a query string untouched and reads as
     * opaque. The opacity is presentation, not security - anyone can decode
     * it. It is encoded so that clients treat it as a token to hand back
     * rather than as a documented format to construct, which would freeze the
     * sort key into the public API.
     */
    static String encode(Instant createdAt, UUID id) {
        String raw = createdAt.toString() + SEPARATOR + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor) {
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw malformed();
        }

        int separator = raw.indexOf(SEPARATOR);
        if (separator < 0) {
            throw malformed();
        }
        try {
            return new Position(
                    Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (DateTimeParseException | IllegalArgumentException notAPosition) {
            throw malformed();
        }
    }

    /**
     * Deliberately says nothing about what was wrong with it. A cursor is not
     * a field the client composed, so naming the part that failed to parse
     * helps nobody debug anything - the only real fix is to start from the
     * first page again, which is what this says.
     */
    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("Malformed cursor - omit it to start from the first page");
    }

    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private record Position(Instant createdAt, UUID id) {
    }
}
