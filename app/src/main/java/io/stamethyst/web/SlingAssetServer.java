package io.stamethyst.web;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

/** Private loopback origin: Gecko's external APK cannot read the launcher's APK assets directly. */
public final class SlingAssetServer {
    private static SlingAssetServer instance;
    private final Context context;
    private final ServerSocket socket;
    private final String prefix;
    private final ConcurrentHashMap<String, SlingLauncherChannel> channels = new ConcurrentHashMap<>();

    public static synchronized void register(Context context, SlingLauncherChannel channel) throws IOException {
        if (instance == null) instance = new SlingAssetServer(context);
        instance.channels.put(channel.token, channel);
    }
    public static synchronized void unregister(SlingLauncherChannel channel) {
        channel.close();
        if (instance != null) instance.channels.remove(channel.token);
    }
    private final ExecutorService clients = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> {
            Thread thread = new Thread(task, "SlingAssetClient"); thread.setDaemon(true); return thread;
        });

    private SlingAssetServer(Context context) throws IOException {
        this.context = context.getApplicationContext();
        SharedPreferences preferences = this.context.getSharedPreferences("sling-gecko-origin", Context.MODE_PRIVATE);
        ServerSocket bound = new ServerSocket();
        int port = preferences.getInt("port", 0);
        try { bound.bind(new InetSocketAddress("127.0.0.1", port), 8); }
        catch (IOException e) {
            bound.close();
            bound = new ServerSocket();
            bound.bind(new InetSocketAddress("127.0.0.1", 0), 8);
        }
        socket = bound;
        String token = preferences.getString("token", null);
        if (token == null) token = UUID.randomUUID().toString();
        preferences.edit().putInt("port", socket.getLocalPort()).putString("token", token).apply();
        prefix = "/" + token + "/";
        Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    try { clients.execute(() -> serve(client)); }
                    catch (RejectedExecutionException e) { client.close(); }
                } catch (IOException e) { if (!socket.isClosed()) android.util.Log.w("SlingAssets", "Accept failed", e); }
            }
        }, "SlingAssetServer");
        acceptor.setDaemon(true);
        acceptor.start();
    }
    public static synchronized String url(Context context, String assetUrl) throws IOException {
        if (!assetUrl.startsWith("file:///android_asset/slingbreak/")) throw new IOException("Unsupported Sling asset URL");
        if (instance == null) instance = new SlingAssetServer(context);
        return "http://127.0.0.1:" + instance.socket.getLocalPort() + instance.prefix
            + assetUrl.substring("file:///android_asset/".length());
    }
    private void serve(Socket client) {
        try (Socket ignored = client) {
            client.setSoTimeout(5000);
            InputStream input = client.getInputStream();
            ByteArrayOutputStream request = new ByteArrayOutputStream();
            int tail = 0;
            while (request.size() < 16384) {
                int next = input.read();
                if (next < 0) return;
                request.write(next);
                tail = (tail << 8) | next;
                if (tail == 0x0d0a0d0a) break;
            }
            if (tail != 0x0d0a0d0a) return;
            String headers = request.toString("ISO-8859-1");
            String[] parts = headers.substring(0, headers.indexOf("\r\n")).split(" ");
            OutputStream out = client.getOutputStream();
            if (parts.length != 3 || !parts[0].equals("GET")) { respond(out, 405); return; }
            String rawPath = parts[1].split("\\?", 2)[0];
            String path = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8");
            String expectedHost = "127.0.0.1:" + socket.getLocalPort();
            java.util.Map<String, String> headerValues = new java.util.HashMap<>();
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) headerValues.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
            }
            // Reject DNS rebinding and cross-origin bridge requests; no CORS opt-in is provided.
            if (!expectedHost.equals(headerValues.get("host"))) { respond(out, 403); return; }
            if (path.startsWith(prefix + "bridge/")) {
                if (!"1".equals(headerValues.get("x-sling-bridge")) ||
                    (headerValues.containsKey("origin") && !("http://" + expectedHost).equals(headerValues.get("origin"))) ||
                    "cross-site".equals(headerValues.get("sec-fetch-site"))) { respond(out, 403); return; }
                String[] bridge = path.substring((prefix + "bridge/").length()).split("/", -1);
                if (bridge.length != 2) { respond(out, 404); return; }
                SlingLauncherChannel channel = channels.get(bridge[0]);
                if (channel == null) { respond(out, 410); return; }
                String body = "[]";
                if (bridge[1].equals("poll")) body = channel.poll();
                else if (bridge[1].equals("onPageReady") || bridge[1].equals("enterGame") || bridge[1].equals("scriptError"))
                    channel.dispatch(bridge[1], query(parts[1], "detail"));
                else { respond(out, 404); return; }
                writeHeaders(out, "application/json; charset=utf-8");
                out.write(body.getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (!path.startsWith(prefix + "slingbreak/") || path.contains("\\") || path.contains("\u0000")) { respond(out, 404); return; }
            for (String segment : path.split("/")) if (segment.equals("..") || segment.equals(".")) { respond(out, 404); return; }
            String asset = path.substring(prefix.length());
            try (InputStream stream = context.getAssets().open(asset)) {
                String extension = MimeTypeMap.getFileExtensionFromUrl(asset);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (asset.endsWith(".js")) mime = "application/javascript";
                if (asset.endsWith(".wasm")) mime = "application/wasm";
                if (mime == null) mime = "application/octet-stream";
                writeHeaders(out, mime);
                String channel = query(parts[1], "geckoLauncher");
                if (asset.equals("slingbreak/index.html") && channels.containsKey(channel)) {
                    ByteArrayOutputStream html = new ByteArrayOutputStream();
                    copy(stream, html);
                    String page = html.toString("UTF-8");
                    ByteArrayOutputStream script = new ByteArrayOutputStream();
                    try (InputStream bridge = context.getAssets().open("sling-gecko-launcher-bridge.js")) { copy(bridge, script); }
                    int head = page.indexOf("<head>");
                    if (head < 0) throw new IOException("Sling HTML has no head");
                    page = page.substring(0, head + 6) + "<script>" + script.toString("UTF-8") + "</script>" + page.substring(head + 6);
                    out.write(page.getBytes(StandardCharsets.UTF_8));
                    return;
                }
                byte[] buffer = new byte[32768];
                for (int n; (n = stream.read(buffer)) >= 0;) out.write(buffer, 0, n);
            } catch (FileNotFoundException e) { respond(out, 404); }
        } catch (IOException | IllegalArgumentException ignored) { }
    }
    private static String query(String target, String key) throws UnsupportedEncodingException {
        int separator = target.indexOf('?');
        if (separator < 0) return "";
        for (String part : target.substring(separator + 1).split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(key)) return pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "";
        }
        return "";
    }
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768];
        for (int n; (n = in.read(buffer)) >= 0;) out.write(buffer, 0, n);
    }
    private static void writeHeaders(OutputStream out, String mime) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime + "\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static void respond(OutputStream out, int status) throws IOException {
        out.write(("HTTP/1.1 " + status + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }
}
