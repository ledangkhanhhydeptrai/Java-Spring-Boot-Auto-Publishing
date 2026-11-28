package com.socialhub.grouppublishingtask.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** The text and single-media API shape validated by the operator. No application-level POST retry. */
final class UnipilePilotClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key, account;
    UnipilePilotClient(String key, String account) {
        if (key == null || key.isBlank() || account == null || !account.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Unipile credentials missing or invalid");
        this.key=key; this.account=account;
    }
    GroupProductionPublisher.Result publish(String group, String text) {
        return publish(group,text,null);
    }
    static Map<String,Object> payload(String text,UnipileMediaResolver.Attachment media) {
        if (text==null || (text.isBlank() && media==null)) throw new IllegalArgumentException("Empty post");
        return media==null ? Map.of("text",text) : Map.of("text",text,"attachments",java.util.List.of(media.json()));
    }
    GroupProductionPublisher.Result publish(String group,String text,UnipileMediaResolver.Attachment media) {
        var unknown = new GroupProductionPublisher.Result(GroupProductionPublisher.Outcome.NEEDS_REVIEW,null);
        if (group == null || !group.matches("[0-9]+") || text == null || (text.isBlank() && media==null)) return unknown;
        try {
            var request = HttpRequest.newBuilder(URI.create("https://api.unipile.com/v2/"+account+"/groups/"+group+"/posts"))
                    .timeout(Duration.ofSeconds(media==null ? 45 : 300)).header("X-API-KEY",key).header("Accept","application/json")
                    .header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(text,media)),StandardCharsets.UTF_8)).build();
            var response = http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(response.statusCode(),response.body(),group,text);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return unknown; }
        catch (Exception failure) { return unknown; }
    }
    GroupProductionPublisher.Result parse(int status,String body,String group,String text) {
        var unknown = new GroupProductionPublisher.Result(GroupProductionPublisher.Outcome.NEEDS_REVIEW,null);
        // Even non-2xx is conservatively unknown until provider failure semantics are verified.
        if (status<200 || status>=300) return unknown;
        try {
            var node=json.readTree(body);
            String id=node.path("id").asText(""), link=node.path("share_url").asText("");
            if (!"Post".equals(node.path("object").asText()) || !id.matches("[0-9]+_[0-9]+")
                    || !GroupPublicationLink.matches(group,link) || !text.equals(node.path("text").asText())
                    || !URI.create(link).getPath().replaceAll("/$","").endsWith("/"+id.substring(id.indexOf('_')+1))) return unknown;
            return new GroupProductionPublisher.Result(GroupProductionPublisher.Outcome.PUBLISHED,link,id);
        } catch(Exception invalid) { return unknown; }
    }
}
