package com.socialhub.grouppublishingtask;


import com.socialhub.grouppublishingtask.excel.GroupPlanSlots;

import java.time.*;
import java.util.*;

public class GroupPlanSlotsTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static OffsetDateTime at(String s) {
        return OffsetDateTime.parse(s + "+07:00");
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS: " + label);
    }

    public static void main(String[] args) {
        var start = at("2026-11-27T09:00:00");
        var slots = new GroupPlanSlots(start, ZONE, 10, 10, List.of());
        Set<Instant> unique = new HashSet<>();
        Map<LocalDate, Integer> counts = new HashMap<>();
        OffsetDateTime prev = null;
        boolean gap = true;
        for (int i = 0; i < 1000; i++) {
            var value = slots.next();
            unique.add(value.toInstant());
            counts.merge(value.toLocalDate(), 1, Integer::sum);
            if (prev != null && Duration.between(prev, value).toMinutes() < 10) gap = false;
            prev = value;
        }
        check(unique.size() == 1000, "1000 unique new slots");
        check(counts.size() == 100 && counts.values().stream().allMatch(v -> v == 10), "daily cap 10 across 100 days");
        check(gap, "minimum gap throughout plan");
        slots = new GroupPlanSlots(start, ZONE, 10, 2, List.of(start.plusMinutes(5)));
        check(slots.next().equals(start.plusMinutes(15)), "avoid existing future slot and respect gap");
        check(slots.next().equals(start.plusDays(1)), "existing task counts toward day limit");
        var late = at("2026-11-27T23:55:00");
        slots = new GroupPlanSlots(late, ZONE, 10, 10, List.of());
        check(slots.next().equals(late) && slots.next().equals(late.plusDays(1)), "midnight rolls to next day start");
        slots = new GroupPlanSlots(start.withOffsetSameInstant(ZoneOffset.UTC), ZONE, 10, 10, List.of());
        check(slots.next().equals(start), "UTC request converted to Vietnam timezone");
        slots = new GroupPlanSlots(start, ZONE, 10, 2, List.of(start.minusMinutes(30), start.minusMinutes(20)));
        check(slots.next().equals(start.plusDays(1)), "earlier same-day existing tasks count");
    }
}
