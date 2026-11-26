package com.socialhub.grouppublishingtask.excel;

import java.time.*;
import java.util.*;

/**
 * Local calendar-day planning only. Does not claim, approve or publish tasks.
 */
public class GroupPlanSlots {
    private final ZoneId zone;
    private final LocalTime firstTime;
    private final int gapMinutes;
    private final int dailyLimit;
    private final LocalDate deadline;
    private final NavigableSet<Instant> occupied = new TreeSet<>();
    private final Map<LocalDate, Integer> counts = new HashMap<>();
    private ZonedDateTime cursor;

    public GroupPlanSlots(OffsetDateTime start, ZoneId zone, int gapMinutes, int dailyLimit,
                          Collection<OffsetDateTime> existing) {
        this.zone = Objects.requireNonNull(zone);
        if (gapMinutes < 1 || dailyLimit < 1) throw new IllegalArgumentException("Positive limits required");
        this.gapMinutes = gapMinutes;
        this.dailyLimit = dailyLimit;
        cursor = Objects.requireNonNull(start).atZoneSameInstant(zone);
        firstTime = cursor.toLocalTime();
        deadline = cursor.toLocalDate().plusYears(5);
        for (OffsetDateTime value : existing) if (value != null) reserve(value.atZoneSameInstant(zone));
    }

    private void reserve(ZonedDateTime value) {
        occupied.add(value.toInstant());
        counts.merge(value.toLocalDate(), 1, Integer::sum);
    }

    private ZonedDateTime firstOn(LocalDate date) {
        return date.atTime(firstTime).atZone(zone);
    }

    public OffsetDateTime next() {
        while (!cursor.toLocalDate().isAfter(deadline)) {
            if (cursor.toLocalTime().isBefore(firstTime)) cursor = firstOn(cursor.toLocalDate());
            if (counts.getOrDefault(cursor.toLocalDate(), 0) >= dailyLimit) {
                cursor = firstOn(cursor.toLocalDate().plusDays(1));
                continue;
            }
            Instant now = cursor.toInstant();
            Instant floor = occupied.floor(now);
            if (floor != null && floor.plusSeconds(gapMinutes * 60L).isAfter(now)) {
                cursor = floor.plusSeconds(gapMinutes * 60L).atZone(zone);
                continue;
            }
            Instant ceil = occupied.ceiling(now);
            if (ceil != null && now.plusSeconds(gapMinutes * 60L).isAfter(ceil)) {
                cursor = ceil.plusSeconds(gapMinutes * 60L).atZone(zone);
                continue;
            }
            ZonedDateTime accepted = cursor;
            reserve(accepted);
            cursor = accepted.plusMinutes(gapMinutes);
            return accepted.toOffsetDateTime();
        }
        throw new IllegalStateException("No available Group schedule slot within five years; reduce selected scope");
    }
}
