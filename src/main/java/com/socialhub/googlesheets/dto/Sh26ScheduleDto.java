package com.socialhub.googlesheets.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class Sh26ScheduleDto {
    private Sh26ScheduleDto() { }

    public record Settings(LocalDate startDate, LocalTime startTime,
                           int intervalMinutes, int maxPostsPerDay) { }
    public record Capability(boolean publisherEnabled, boolean liveMode,
                             boolean multiJobEnabled, boolean videoEnabled,
                             boolean reelsEnabled, boolean bulkEnabled, boolean excelAutoPublishEnabled,
                             boolean canConfirm, String earliestNonOverlappingDate, String message) { }
    public record Group(String group, UUID importId, int selected, int rescheduled,
                        int keepOriginal, int blocked, int alreadyPlanned,
                        String previewToken, int createdJobs, String state, String message) { }
    public record Preview(Capability capability, int selected, int blocked,
                          int alreadyPlanned, List<Group> groups) { }
    public record Confirm(Settings settings, List<GroupToken> tokens,
                          String confirmation) { }
    public record GroupToken(String group, UUID importId, String previewToken) { }
    public record Result(int createdJobs, int blocked, int alreadyPlanned,
                         List<Group> groups, String message) { }
    public record JobStatus(String group, int totalJobs, int pending,
                            int published, int failed, int processing, int other) { }
}
