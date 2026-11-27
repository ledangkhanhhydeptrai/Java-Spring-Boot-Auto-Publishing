package com.socialhub.grouppublishingtask;
import com.socialhub.grouppublishingtask.dispatch.GroupDispatchEnvelope;
import com.socialhub.grouppublishingtask.dispatch.GroupPublicationLink;

import java.util.UUID;
import java.nio.charset.StandardCharsets;
public class GroupDispatchContractTest {
    private static int checks;
    static void check(boolean condition) { checks++; if(!condition) throw new AssertionError("check "+checks); }
    static void rejects(byte[] bytes) { try { GroupDispatchEnvelope.decode(bytes); throw new AssertionError("accepted invalid envelope"); } catch(IllegalArgumentException expected) { checks++; } }
    public static void main(String[] args) {
        var event = new GroupDispatchEnvelope(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        check(event.equals(GroupDispatchEnvelope.decode(event.encode())));
        check(java.util.Arrays.equals(event.encode(),event.encode()));
        rejects(new byte[0]); rejects("not-an-event".getBytes(StandardCharsets.US_ASCII));
        var malformed=event.encode(); malformed[0]=(byte)'z'; rejects(malformed);
        check(GroupDispatchEnvelope.retryDelaySeconds(1)==15);
        check(GroupDispatchEnvelope.retryDelaySeconds(2)==30);
        check(GroupDispatchEnvelope.retryDelaySeconds(20)==900);
        check(GroupPublicationLink.matches("123","https://www.facebook.com/groups/123/posts/456/"));
        check(GroupPublicationLink.matches("123","https://facebook.com/groups/123/permalink/456"));
        check(!GroupPublicationLink.matches("123","https://www.facebook.com/groups/124/posts/456"));
        check(!GroupPublicationLink.matches("123","https://facebook.com.evil.test/groups/123/posts/456"));
        check(!GroupPublicationLink.matches("123","https://evil@facebook.com/groups/123/posts/456"));
        check(!GroupPublicationLink.matches("123","http://facebook.com/groups/123/posts/456"));
        check(!GroupPublicationLink.matches("123","https://facebook.com/groups/123/posts/456?x=y"));
        check(!GroupPublicationLink.matches("123","https://facebook.com/groups/123/posts/%34%35%36"));
        System.out.println("PASS: "+checks+" Java contract checks");
    }
}
