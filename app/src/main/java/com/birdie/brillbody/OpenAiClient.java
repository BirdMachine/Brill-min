package com.birdie.brillbody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Minimal dependency-free OpenAI HTTP client for the prototype. */
public final class OpenAiClient {
    private static final int CONNECT_TIMEOUT_MS = 25_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    public static final class Transcription {
        public final String text;
        public final int inputTokens;
        public final int audioTokens;
        public final double confidence;
        public final long requestDurationMs;

        public Transcription(String text, int inputTokens, int audioTokens, double confidence, long requestDurationMs) {
            this.text = text;
            this.inputTokens = inputTokens;
            this.audioTokens = audioTokens;
            this.confidence = confidence;
            this.requestDurationMs = requestDurationMs;
        }
    }

    public static final class BrainResponse {
        public final String id;
        public final String text;
        public final int inputTokens;
        public final int outputTokens;
        public final long requestDurationMs;

        public BrainResponse(String id, String text, int inputTokens, int outputTokens, long requestDurationMs) {
            this.id = id;
            this.text = text;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.requestDurationMs = requestDurationMs;
        }
    }

    public static final class ApiException extends IOException {
        public final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private final String baseUrl;
    private final String apiKey;

    public OpenAiClient(String baseUrl, String apiKey) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public Transcription transcribe(File wavFile, String model) throws Exception {
        requireKey();
        long started = System.currentTimeMillis();
        String boundary = "----BrillBoundary" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open("/audio/transcriptions", "POST");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setChunkedStreamingMode(8192);

        try (OutputStream raw = new BufferedOutputStream(connection.getOutputStream())) {
            writeTextPart(raw, boundary, "model", model);
            writeTextPart(raw, boundary, "response_format", "json");
            // Supported by current gpt-4o transcription models. Other compatible
            // endpoints may ignore it or omit logprobs from their response.
            writeTextPart(raw, boundary, "include[]", "logprobs");
            writeFilePart(raw, boundary, "file", wavFile, "audio/wav");
            raw.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        }

        String body = readResponse(connection);
        JSONObject json = new JSONObject(body);
        String text = json.optString("text", "").trim();
        if (text.isEmpty()) throw new IOException("The transcription endpoint returned no text.");

        JSONObject usage = json.optJSONObject("usage");
        int inputTokens = usage == null ? -1 : usage.optInt("input_tokens", -1);
        int audioTokens = -1;
        if (usage != null) {
            JSONObject details = usage.optJSONObject("input_token_details");
            if (details != null) audioTokens = details.optInt("audio_tokens", -1);
        }
        double confidence = confidenceFromLogprobs(json.optJSONArray("logprobs"));
        return new Transcription(text, inputTokens, audioTokens, confidence, System.currentTimeMillis() - started);
    }

    public BrainResponse respond(
            String model,
            String instructions,
            String userText,
            String previousResponseId,
            String identityId,
            String bodyId) throws Exception {
        requireKey();
        long started = System.currentTimeMillis();

        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("instructions", instructions +
                "\n\nRuntime context: shared identity ID=" + identityId +
                "; current body ID=" + bodyId + ".");
        payload.put("input", userText);
        payload.put("max_output_tokens", 900);
        if (previousResponseId != null && !previousResponseId.trim().isEmpty()) {
            payload.put("previous_response_id", previousResponseId.trim());
        }

        HttpURLConnection connection = open("/responses", "POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = new BufferedOutputStream(connection.getOutputStream())) {
            output.write(bytes);
        }

        String body = readResponse(connection);
        JSONObject json = new JSONObject(body);
        String id = json.optString("id", "");
        String text = extractOutputText(json);
        if (text.isEmpty()) throw new IOException("The response contained no output text.");

        JSONObject usage = json.optJSONObject("usage");
        int inputTokens = usage == null ? -1 : usage.optInt("input_tokens", -1);
        int outputTokens = usage == null ? -1 : usage.optInt("output_tokens", -1);
        return new BrainResponse(id, text, inputTokens, outputTokens, System.currentTimeMillis() - started);
    }

    private static double confidenceFromLogprobs(JSONArray logprobs) {
        if (logprobs == null || logprobs.length() == 0) return -1.0;
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < logprobs.length(); i++) {
            JSONObject token = logprobs.optJSONObject(i);
            if (token == null || !token.has("logprob")) continue;
            double value = token.optDouble("logprob", Double.NaN);
            if (Double.isNaN(value) || Double.isInfinite(value)) continue;
            sum += value;
            count++;
        }
        if (count == 0) return -1.0;
        // exp(mean log probability) gives a compact 0..1 geometric-mean score.
        return Math.max(0.0, Math.min(1.0, Math.exp(sum / count)));
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoInput(true);
        if (!"GET".equals(method)) connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "BrillBodyAndroid/0.2");
        return connection;
    }

    private void requireKey() {
        if (apiKey.isEmpty()) throw new IllegalStateException("Open Brill settings and add an API key first.");
    }

    private static void writeTextPart(OutputStream output, String boundary, String name, String value) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write((value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(OutputStream output, String boundary, String name, File file, String mimeType) throws IOException {
        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + file.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String readResponse(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : readFully(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new ApiException(status, extractErrorMessage(body, status));
        }
        return body;
    }

    private static String readFully(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String extractErrorMessage(String body, int status) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) return "OpenAI HTTP " + status + ": " + message;
            }
        } catch (JSONException ignored) {
        }
        String compact = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (compact.length() > 400) compact = compact.substring(0, 400) + "…";
        return compact.isEmpty() ? "OpenAI request failed with HTTP " + status : "OpenAI HTTP " + status + ": " + compact;
    }

    private static String extractOutputText(JSONObject response) {
        StringBuilder result = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                String type = part.optString("type", "");
                if ("output_text".equals(type)) {
                    appendParagraph(result, part.optString("text", ""));
                } else if ("refusal".equals(type)) {
                    appendParagraph(result, part.optString("refusal", part.optString("text", "")));
                }
            }
        }
        return result.toString().trim();
    }

    private static void appendParagraph(StringBuilder result, String text) {
        String clean = text == null ? "" : text.trim();
        if (clean.isEmpty()) return;
        if (result.length() > 0) result.append("\n\n");
        result.append(clean);
    }

    private static String trimTrailingSlash(String url) {
        String result = url == null || url.trim().isEmpty() ? AppConfig.DEFAULT_BASE_URL : url.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
