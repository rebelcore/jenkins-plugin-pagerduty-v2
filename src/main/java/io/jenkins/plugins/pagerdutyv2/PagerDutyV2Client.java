package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.ProxyConfiguration;
import jenkins.model.Jenkins;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HTTP client for PagerDuty Events API v2.
 *
 * Retries on 429 and 5xx with exponential backoff + jitter.
 * Reuses a process-wide OkHttp client (connection pool, dispatcher).
 * Honors Jenkins {@link ProxyConfiguration}.
 */
public class PagerDutyV2Client {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Max attempts including the initial try. */
    static final int MAX_ATTEMPTS = 4;
    /** Base backoff in millis; doubles each attempt; jittered ±25%. */
    static final long BASE_BACKOFF_MS = 500L;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static volatile OkHttpClient SHARED;

    private final OkHttpClient http;
    private final String endpointUrl;

    public PagerDutyV2Client(@NonNull String endpointUrl) {
        this(endpointUrl, sharedClient());
    }

    /** Test seam: inject a custom client (e.g. zero-backoff for fast tests). */
    PagerDutyV2Client(@NonNull String endpointUrl, @NonNull OkHttpClient http) {
        this.endpointUrl = endpointUrl;
        this.http = http;
    }

    private static OkHttpClient sharedClient() {
        OkHttpClient c = SHARED;
        if (c != null) {
            return c;
        }
        synchronized (PagerDutyV2Client.class) {
            if (SHARED == null) {
                OkHttpClient.Builder b = new OkHttpClient.Builder()
                        .callTimeout(Duration.ofSeconds(15))
                        .connectTimeout(Duration.ofSeconds(10))
                        .readTimeout(Duration.ofSeconds(15));
                Proxy proxy = jenkinsProxy();
                if (proxy != null) {
                    b.proxy(proxy);
                }
                SHARED = b.build();
            }
            return SHARED;
        }
    }

    private static Proxy jenkinsProxy() {
        try {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j == null) {
                return null;
            }
            ProxyConfiguration pc = j.getProxy();
            if (pc == null) {
                return null;
            }
            // createProxy(host) uses the host to honor no-proxy rules
            return pc.createProxy("events.pagerduty.com");
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void postEvent(@NonNull Map<String, Object> body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);

        IOException lastIo = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Request req = new Request.Builder()
                    .url(endpointUrl)
                    .post(RequestBody.create(bytes, JSON))
                    .build();

            int code = -1;
            String snippet = "";
            try (Response resp = http.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    return;
                }
                code = resp.code();
                snippet = bodySnippet(resp);
            } catch (IOException io) {
                lastIo = io;
                if (attempt == MAX_ATTEMPTS) {
                    throw io;
                }
                sleepBackoff(attempt);
                continue;
            }

            // HTTP error response: decide retry or surface the failure.
            if (!isRetryable(code) || attempt == MAX_ATTEMPTS) {
                throw new IOException("PagerDuty enqueue failed: HTTP " + code + " " + snippet);
            }
            sleepBackoff(attempt);
        }
        if (lastIo != null) {
            throw lastIo;
        }
    }

    private static boolean isRetryable(int code) {
        return code == 429 || (code >= 500 && code < 600);
    }

    private static String bodySnippet(Response resp) {
        try {
            okhttp3.ResponseBody rb = resp.body();
            if (rb == null) return "";
            String s = rb.string();
            return s.length() > 500 ? s.substring(0, 500) + "..." : s;
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleepBackoff(int attempt) {
        long base = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = (long) (base * 0.25);
        long delta = jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(-jitter, jitter + 1);
        long sleep = Math.max(0L, base + delta);
        if (sleep == 0L) return;
        try {
            Thread.sleep(sleep);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
