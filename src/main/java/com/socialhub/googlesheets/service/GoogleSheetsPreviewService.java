package com.socialhub.googlesheets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.socialhub.common.ApiException;
import com.socialhub.googlesheets.dto.GoogleSheetsDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GoogleSheetsPreviewService {
    private static final String READONLY_SCOPE =
            "https://www.googleapis.com/auth/spreadsheets.readonly";
    private static final Pattern SHEET_URL = Pattern.compile(
            "^https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]{20,})" +
            "(?:[/\\?#].*)?$");
    // Bounded A1 notation, optional tab name. Supports e.g. A1:H30 or 'Bảng 1'!A1:H30.
    private static final Pattern RANGE = Pattern.compile(
            "^(?:(?:'[^'\\r\\n]{1,80}'|[A-Za-z0-9 _-]{1,80})!)?" +
            "([A-Za-z]{1,2})([1-9][0-9]{0,5}):([A-Za-z]{1,2})([1-9][0-9]{0,5})$");
    private static final int MAX_ROWS = 200;
    private static final int MAX_COLUMNS = 26;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper;

    public GoogleSheetsPreviewService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public GoogleSheetsDto.Preview preview(GoogleSheetsDto.PreviewRequest request) {
        String spreadsheetId = spreadsheetId(request.spreadsheetUrl());
        String range = request.range() == null || request.range().isBlank()
                ? "A1:H30" : request.range().trim();
        Matcher match = RANGE.matcher(range);
        if (!match.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Invalid A1 range; example: A1:H30 or 'Sheet 1'!A1:H30");
        }
        int startCol = col(match.group(1));
        int endCol = col(match.group(3));
        int startRow = Integer.parseInt(match.group(2));
        int endRow = Integer.parseInt(match.group(4));
        if (endRow < startRow || endRow - startRow + 1 > MAX_ROWS ||
                endCol < startCol || endCol > MAX_COLUMNS) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Preview supports at most 200 rows and columns A..Z per request");
        }

        final String token;
        try {
            GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of(READONLY_SCOPE));
            // Do not log or expose this token. Refresh managed by the Google Auth library.
            credentials.refreshIfExpired();
            if (credentials.getAccessToken() == null) {
                credentials.refresh();
            }
            token = credentials.getAccessToken().getTokenValue();
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google credentials unavailable; verify GOOGLE_APPLICATION_CREDENTIALS on the Spring Boot process");
        }

        String endpoint = "https://sheets.googleapis.com/v4/spreadsheets/"
                + spreadsheetId + "/values/" + encode(range)
                + "?valueRenderOption=FORMATTED_VALUE&majorDimension=ROWS";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403) {
                throw new ApiException(HttpStatus.FORBIDDEN,
                        "Google Sheets denied access; share this Sheet with the Service Account as Viewer and enable Sheets API");
            }
            if (response.statusCode() == 404) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "Google Sheet or tab/range not found (check URL and range)");
            }
            if (response.statusCode() == 400) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "Invalid Google Sheets range/tab; verify the tab name and A1 notation");
            }
            if (response.statusCode() == 429 || response.statusCode() >= 500) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Google Sheets API temporarily unavailable or rate limited");
            }
            if (response.statusCode() != 200) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "Google Sheets API request failed (HTTP " + response.statusCode() + ")");
            }

            JsonNode values = mapper.readTree(response.body()).path("values");
            List<GoogleSheetsDto.SheetRow> rows = new ArrayList<>();
            if (values.isArray()) {
                int number = startRow;
                for (JsonNode row : values) {
                    List<String> cells = new ArrayList<>();
                    if (row.isArray()) row.forEach(cell -> cells.add(cell.asText("")));
                    rows.add(new GoogleSheetsDto.SheetRow(number++, List.copyOf(cells)));
                }
            }
            return new GoogleSheetsDto.Preview(spreadsheetId, range, rows.size(), List.copyOf(rows));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Sheets request interrupted");
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Unable to reach or parse Google Sheets API response");
        }
    }

    private static String spreadsheetId(String url) {
        if (url == null || url.length() > 2048) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Valid Google Sheets URL is required");
        }
        Matcher match = SHEET_URL.matcher(url.trim());
        if (!match.matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Only https://docs.google.com/spreadsheets/d/{id}/edit URLs are accepted");
        }
        return match.group(1);
    }

    private static int col(String text) {
        int result = 0;
        for (char c : text.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            result = result * 26 + c - 'A' + 1;
        }
        return result;
    }

    private static String encode(String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
