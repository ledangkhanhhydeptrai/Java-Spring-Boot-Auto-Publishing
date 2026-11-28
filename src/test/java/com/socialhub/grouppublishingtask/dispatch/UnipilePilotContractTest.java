package com.socialhub.grouppublishingtask.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Offline parser checks only; does not send network requests.
 */
public class UnipilePilotContractTest {
    private static int checks;

    static void check(boolean value) {
        if (!value) throw new AssertionError("check " + (checks + 1));
        checks++;
    }

    public static void main(String[] args) throws Exception {
        var client = new UnipilePilotClient("not-a-real-key", "acc_test");
        var json = new ObjectMapper();
        String text = "Bài kiểm thử tiếng Việt\nDòng thứ hai";
        String link = "https://www.facebook.com/groups/123/posts/456/";
        String good = json.writeValueAsString(Map.of("object", "Post", "id", "789_456", "share_url", link, "text", text));
        var result = client.parse(201, good, "123", text);
        check(result.outcome() == GroupProductionPublisher.Outcome.PUBLISHED);
        check(link.equals(result.permalink()));
        check("789_456".equals(result.remotePostId()));
        for (int code : new int[]{301, 400, 401, 403, 404, 429, 500, 503})
            check(client.parse(code, good, "123", text).outcome() == GroupProductionPublisher.Outcome.NEEDS_REVIEW);
        for (String bad : new String[]{"{}", "not-json", good.replace("groups/123", "groups/999"), good.replace("789_456", "789_457"), good.replace("www.facebook.com", "www.facebook.com.evil.test")})
            check(client.parse(200, bad, "123", text).outcome() == GroupProductionPublisher.Outcome.NEEDS_REVIEW);
        check(client.parse(200, good, "123", "other text").outcome() == GroupProductionPublisher.Outcome.NEEDS_REVIEW);
        check(UnipilePilotPayload.hash("abc").equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check(!UnipilePilotPayload.hash(text).equals(UnipilePilotPayload.hash(text + " ")));
        System.out.println("PASS: " + checks + " Unipile offline contract checks");
    }
}
