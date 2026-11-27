package com.socialhub.grouppublishingtask.dispatch;

import java.net.URI;
import java.util.Set;

/** Conservative exact-group URL check. It is NOT proof of remote publication. */
public final class GroupPublicationLink {
    private static final Set<String> HOSTS = Set.of("facebook.com", "www.facebook.com", "m.facebook.com");
    public static boolean matches(String externalGroupId, String link) {
        if (externalGroupId == null || !externalGroupId.matches("[0-9]+") || link == null || link.length() > 2048) return false;
        try {
            URI uri = URI.create(link);
            if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) return false;
            return uri.getRawPath().matches("/groups/" + externalGroupId + "/(posts|permalink)/[0-9]+/?");
        } catch (RuntimeException invalid) { return false; }
    }
}
