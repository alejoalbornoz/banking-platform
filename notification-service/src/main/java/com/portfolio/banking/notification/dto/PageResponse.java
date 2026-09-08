package com.portfolio.banking.notification.dto;

import java.util.List;

/**
 * One page of results, plus how to ask for the one after it.
 * <p>
 * There is deliberately no total count and no page number. Both would cost a
 * second query over the whole table on every request, and neither is stable
 * on data that keeps growing while a client reads it - a "page 3 of 47" is
 * already wrong by the time it renders.
 *
 * @param nextCursor opaque token to send back as {@code ?cursor=} to get the
 *                    next page. {@code null} means this was the last one, so
 *                    a client loop is just "keep paging while nextCursor is
 *                    not null".
 */
public record PageResponse<T>(List<T> items, String nextCursor) {
}
