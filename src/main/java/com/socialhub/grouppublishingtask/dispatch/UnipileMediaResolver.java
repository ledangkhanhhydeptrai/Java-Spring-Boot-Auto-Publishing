package com.socialhub.grouppublishingtask.dispatch;

import java.io.IOException;
import java.net.URI;
import javax.net.ssl.HttpsURLConnection;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/** Conservative pilot: one public versioned Cloudinary asset, no redirects, bounded download. */
final class UnipileMediaResolver {
    static final int MAX_BYTES = 1024 * 1024;
    record Attachment(String filename,String contentType,String content) {
        Map<String,String> json() { return Map.of("filename",filename,"content_type",contentType,"content",content); }
    }
    static boolean allowedReference(String url,String type) {
        try {
            URI u=URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || !"res.cloudinary.com".equalsIgnoreCase(u.getHost())
                    || u.getUserInfo()!=null || u.getPort()!=-1 || u.getRawQuery()!=null || u.getRawFragment()!=null) return false;
            String p=u.getRawPath();
            if (p==null || !p.matches("/[A-Za-z0-9_-]+/(image|video)/upload/v[0-9]+/[A-Za-z0-9_./-]+")
                    || p.contains("..") || p.contains("//")) return false;
            String lower=p.toLowerCase(Locale.ROOT);
            return ("IMAGE".equals(type) && p.matches("/[^/]+/image/.*") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")))
                    || ("VIDEO".equals(type) && p.matches("/[^/]+/video/.*") && lower.endsWith(".mp4"));
        } catch(RuntimeException invalid) { return false; }
    }
    Attachment resolve(String url,String type) throws IOException {
        if (!allowedReference(url,type)) throw new IOException("Unsupported media reference");
        URI uri=URI.create(url);
        HttpsURLConnection c=(HttpsURLConnection)uri.toURL().openConnection(java.net.Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("Accept-Encoding","identity");
        try {
            if (c.getResponseCode()!=200 || c.getContentLengthLong()>MAX_BYTES) throw new IOException("Media download rejected");
            String mime=c.getContentType();
            if (mime==null) throw new IOException("Missing media content type");
            mime=mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            try (var in=c.getInputStream(); var out=new java.io.ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192]; int n;
                while ((n=in.read(chunk))!=-1) {
                    if (System.nanoTime()>deadline || out.size()+n>MAX_BYTES) throw new IOException("Media too large or slow");
                    out.write(chunk,0,n);
                }
                return encode(type,mime,out.toByteArray());
            }
        } finally { c.disconnect(); }
    }
    static Attachment encode(String type,String mime,byte[] bytes) throws IOException {
        if (bytes==null || bytes.length<12 || bytes.length>MAX_BYTES) throw new IOException("Invalid media length");
        String filename;
        if ("IMAGE".equals(type) && "image/jpeg".equals(mime) && (bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) filename="image.jpg";
        else if ("IMAGE".equals(type) && "image/png".equals(mime) && java.util.Arrays.equals(java.util.Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) filename="image.png";
        else if ("VIDEO".equals(type) && "video/mp4".equals(mime) && bytes[4]=='f' && bytes[5]=='t' && bytes[6]=='y' && bytes[7]=='p') filename="video.mp4";
        else throw new IOException("Media signature/type mismatch");
        return new Attachment(filename,mime,Base64.getEncoder().encodeToString(bytes));
    }
}
