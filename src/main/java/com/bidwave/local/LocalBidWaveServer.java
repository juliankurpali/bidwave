package com.bidwave.local;

import com.bidwave.model.Lot;
import com.bidwave.util.Ids;
import com.bidwave.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * Run BidWave with zero AWS: UI + API on one port.
 *
 * <pre>
 *   mvn -s .mvn/settings.xml -q exec:java
 *   open http://localhost:8080/
 * </pre>
 */
public final class LocalBidWaveServer {

    private final InMemoryAuctionStore store = new InMemoryAuctionStore();
    private final Path frontendDir;
    private final int port;

    public LocalBidWaveServer(Path frontendDir, int port) {
        this.frontendDir = frontendDir.toAbsolutePath().normalize();
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        Path frontend = Path.of(System.getProperty("bidwave.frontend", "frontend"));
        int port = Integer.parseInt(System.getProperty("bidwave.port", "8080"));
        if (!Files.isDirectory(frontend)) {
            // running from repo root vs bidwave/
            Path alt = Path.of("bidwave/frontend");
            if (Files.isDirectory(alt)) {
                frontend = alt;
            }
        }
        new LocalBidWaveServer(frontend, port).start();
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/lots", this::handleLots);
        server.createContext("/bids", this::handleBids);
        server.createContext("/health", ex -> writeJson(ex, 200, Map.of("status", "ok", "mode", "local")));
        server.createContext("/", this::handleStatic);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("BidWave LOCAL (no AWS)");
        System.out.println("  UI + API: http://127.0.0.1:" + port + "/");
        System.out.println("  Frontend: " + frontendDir);
        System.out.println("  Config:   served /config.json (local mode, no secrets)");
        System.out.println("Press Ctrl+C to stop.");
    }

    private void handleLots(HttpExchange ex) throws IOException {
        if (corsPreflight(ex)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String path = ex.getRequestURI().getPath();
        if ("/lots".equals(path) || "/lots/".equals(path)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Lot l : store.listLots()) {
                items.add(lotJson(l));
            }
            writeJson(ex, 200, Map.of("lots", items));
            return;
        }

        if (path.startsWith("/lots/")) {
            String rest = path.substring("/lots/".length());
            if (rest.endsWith("/bids") || rest.endsWith("/bids/")) {
                String lotId = rest.replaceAll("/bids/?$", "");
                if (lotId.isBlank() || lotId.contains("/")) {
                    writeJson(ex, 404, Map.of("error", "Not found"));
                    return;
                }
                if (store.getLot(lotId).isEmpty()) {
                    writeJson(ex, 404, Map.of("error", "Lot not found: " + lotId));
                    return;
                }
                List<Map<String, Object>> bids = new ArrayList<>();
                for (InMemoryAuctionStore.BidRecord b : store.listBids(lotId)) {
                    bids.add(bidJson(b));
                }
                writeJson(ex, 200, Map.of("lotId", lotId, "bids", bids));
                return;
            }

            String id = rest;
            if (id.contains("/")) {
                writeJson(ex, 404, Map.of("error", "Not found"));
                return;
            }
            Optional<Lot> lot = store.getLot(id);
            if (lot.isEmpty()) {
                writeJson(ex, 404, Map.of("error", "Lot not found: " + id));
                return;
            }
            writeJson(ex, 200, lotJson(lot.get()));
            return;
        }

        writeJson(ex, 404, Map.of("error", "Not found"));
    }

    private void handleBids(HttpExchange ex) throws IOException {
        if (corsPreflight(ex)) {
            return;
        }
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode node = Json.mapper().readTree(raw);
        String lotId = text(node, "lotId");
        String bidder = text(node, "bidder");
        BigDecimal amount = amount(node);
        if (lotId == null || lotId.isBlank()) {
            writeJson(ex, 400, Map.of("error", "lotId is required"));
            return;
        }
        if (bidder == null || bidder.isBlank()) {
            writeJson(ex, 400, Map.of("error", "bidder is required"));
            return;
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            writeJson(ex, 400, Map.of("error", "amount must be a positive number"));
            return;
        }

        String bidId = Ids.orNew(text(node, "bidId"));
        InMemoryAuctionStore.SettleResult result = store.placeBid(bidId, lotId, bidder, amount);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bidId", bidId);
        body.put("lotId", lotId);
        body.put("status", result.status().name());
        body.put("mode", "local-sync");
        if (result.lot() != null) {
            body.put("lot", lotJson(result.lot()));
        }
        int code = result.status().name().startsWith("REJECTED") || result.status().name().equals("DUPLICATE")
                ? 200
                : 201;
        writeJson(ex, code, body);
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            writeJson(ex, 405, Map.of("error", "Method not allowed"));
            return;
        }

        String path = ex.getRequestURI().getPath();
        if ("/config.json".equals(path)) {
            // Local config map — no secrets. Same-origin API.
            writeJson(ex, 200, Map.of(
                    "mode", "local",
                    "apiBaseUrl", "",
                    "requireApiKey", false));
            return;
        }

        String relative = path.equals("/") ? "index.html" : path.substring(1);
        if (relative.contains("..")) {
            writeJson(ex, 400, Map.of("error", "Bad path"));
            return;
        }

        Path file = frontendDir.resolve(relative).normalize();
        if (!file.startsWith(frontendDir) || !Files.isRegularFile(file)) {
            writeJson(ex, 404, Map.of("error", "Not found"));
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", contentType(file.getFileName().toString()));
        ex.sendResponseHeaders(200, bytes.length);
        if (!"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    private static Map<String, Object> lotJson(Lot l) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lotId", l.getLotId());
        body.put("title", l.getTitle());
        body.put("highBid", l.getHighBid());
        body.put("highBidder", l.getHighBidder());
        body.put("startsAt", l.getStartsAt() != null ? l.getStartsAt().toString() : null);
        body.put("endsAt", l.getEndsAt() != null ? l.getEndsAt().toString() : null);
        body.put("version", l.getVersion());
        return body;
    }

    private static Map<String, Object> bidJson(InMemoryAuctionStore.BidRecord b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bidId", b.bidId());
        body.put("lotId", b.lotId());
        body.put("bidder", b.bidder());
        body.put("amount", b.amount());
        body.put("status", b.status());
        body.put("settledAt", b.settledAt() != null ? b.settledAt().toString() : null);
        return body;
    }

    private static boolean corsPreflight(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "content-type,x-api-key");
        h.set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return true;
        }
        return false;
    }

    private static void writeJson(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json");
        h.set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String contentType(String name) {
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.asText();
    }

    private static BigDecimal amount(JsonNode node) {
        JsonNode v = node.get("amount");
        if (v == null || v.isNull()) return null;
        try {
            return v.isNumber() ? v.decimalValue() : new BigDecimal(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
