package io.jenkins.plugins.pagerdutyv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal HTTP client for PagerDuty Events API v2 using OkHttp (via okhttp-api plugin).
 */
public class PagerDutyV2Client {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String endpointUrl;

    public PagerDutyV2Client(@NonNull String endpointUrl) {
        this.endpointUrl = endpointUrl;
        this.http = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(15))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void postEvent(@NonNull Map<String, Object> body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        Request req = new Request.Builder()
                .url(endpointUrl)
                .post(RequestBody.create(bytes, JSON))
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String msg = "";
                okhttp3.ResponseBody respBody = resp.body();
                if (respBody != null) {
                    msg = respBody.string();
                }
                throw new IOException("PagerDuty enqueue failed: HTTP " + resp.code() + " " + msg);
            }
        }
    }
}