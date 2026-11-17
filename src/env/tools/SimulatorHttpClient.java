package tools;

import java.io.IOException;
import java.util.logging.Logger;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Fault-tolerant HTTP client for the Node-RED simulator.
 *
 * Wraps Apache HttpClient5 with:
 * <ul>
 *   <li>configurable connect / response timeouts;</li>
 *   <li>bounded exponential backoff retry on {@link IOException} and 5xx
 *       responses (4xx are treated as permanent and not retried);</li>
 *   <li>cheap construction — one shared {@link CloseableHttpClient} per
 *       instance, reused for the lifetime of the artifact.</li>
 * </ul>
 *
 * <p>Not a CArtAgO artifact: it is created and configured by {@code LabEnvironment},
 * which uses it for the two POST requests the simulator needs (reset, set state).
 * Defaults: connect timeout 2 s, response timeout 5 s, 3 retries, backoff base
 * 200 ms; {@code LabEnvironment} can override them from the {@code sim.http.*}
 * system properties through {@link #configure}. Safe to call from several threads
 * (settings are volatile, reconfiguration is synchronized).
 */
public class SimulatorHttpClient implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(SimulatorHttpClient.class.getName());

    /** Result of an HTTP exchange. */
    public static final class Result {
        /** HTTP status code. */
        public final int    code;
        /** Response body as text; empty if the response had no body. */
        public final String body;
        public Result(int code, String body) { this.code = code; this.body = body; }
    }

    /** Thrown when all retries are exhausted. */
    public static class SimulatorUnreachableException extends IOException {
        private static final long serialVersionUID = 1L;
        public SimulatorUnreachableException(String message, Throwable cause) { super(message, cause); }
    }

    private volatile int connectTimeoutMs  = 2_000;
    private volatile int responseTimeoutMs = 5_000;
    private volatile int maxRetries        = 3;
    private volatile int backoffBaseMs     = 200;

    private CloseableHttpClient client;

    /** Creates a client with the default timeouts and retry policy. */
    public SimulatorHttpClient() {
        this.client = buildClient();
    }

    /**
     * Reconfigure timeouts and retry policy. Rebuilds the internal client.
     * Negative values are coerced to zero (no timeout / no retry).
     *
     * @param connectMs  connect timeout in milliseconds (also used for leasing a connection)
     * @param responseMs response timeout in milliseconds
     * @param retries    number of retries after the first attempt
     * @param backoffMs  base wait before the first retry in milliseconds; it doubles per retry
     */
    public synchronized void configure(int connectMs, int responseMs, int retries, int backoffMs) {
        this.connectTimeoutMs  = Math.max(0, connectMs);
        this.responseTimeoutMs = Math.max(0, responseMs);
        this.maxRetries        = Math.max(0, retries);
        this.backoffBaseMs     = Math.max(0, backoffMs);
        closeQuietly();
        this.client = buildClient();
        LOGGER.info("SimulatorHttpClient configured: connect=" + connectTimeoutMs +
                    "ms response=" + responseTimeoutMs + "ms retries=" + maxRetries +
                    " backoffBase=" + backoffBaseMs + "ms");
    }

    /** Builds an Apache HttpClient with the current timeouts. */
    private CloseableHttpClient buildClient() {
        RequestConfig reqCfg = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .build();
        return HttpClients.custom()
                .setDefaultRequestConfig(reqCfg)
                .build();
    }

    /**
     * POST a JSON body with retry. Retries on IOException and HTTP 5xx with
     * exponential backoff capped at {@code maxRetries}. HTTP 4xx is returned
     * as-is to the caller (permanent client error — retrying will not help).
     *
     * <p>If the last attempt returns HTTP 5xx, that response is returned
     * (not thrown); the exception is only thrown when the last attempt fails
     * with an I/O error.
     *
     * @param url      full URL to post to
     * @param jsonBody request body, sent as {@code application/json}
     * @return status code and body of the final response
     * @throws SimulatorUnreachableException after all retries are exhausted.
     */
    public Result postJson(String url, String jsonBody) throws IOException {
        IOException lastIo = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpPost post = new HttpPost(url);
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
                try (CloseableHttpResponse resp = client.execute(post)) {
                    int code = resp.getCode();
                    String body;
                    try {
                        body = resp.getEntity() == null ? ""
                                : EntityUtils.toString(resp.getEntity());
                    } catch (ParseException pe) {
                        body = "";
                    }
                    if (code >= 500 && code < 600 && attempt < maxRetries) {
                        long wait = backoff(attempt);
                        LOGGER.warning("postJson " + url + " HTTP " + code +
                                " attempt " + (attempt + 1) + "/" + (maxRetries + 1) +
                                " — retrying in " + wait + "ms");
                        sleep(wait);
                        continue;
                    }
                    return new Result(code, body);
                }
            } catch (IOException e) {
                lastIo = e;
                if (attempt < maxRetries) {
                    long wait = backoff(attempt);
                    LOGGER.warning("postJson " + url + " IOException: " + e.getMessage() +
                            " — attempt " + (attempt + 1) + "/" + (maxRetries + 1) +
                            " — retrying in " + wait + "ms");
                    sleep(wait);
                } else {
                    break;
                }
            }
        }
        throw new SimulatorUnreachableException(
                "simulator_unreachable: POST " + url + " failed after "
                        + (maxRetries + 1) + " attempt(s)", lastIo);
    }

    /** Exponential backoff: base * 2^attempt. */
    private long backoff(int attempt) {
        if (backoffBaseMs <= 0) return 0;
        // Cap shift to avoid overflow.
        int shift = Math.min(attempt, 16);
        return (long) backoffBaseMs << shift;
    }

    /** Sleeps for {@code ms} milliseconds; an interrupt is passed on by setting the thread's interrupt flag. */
    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Closes the underlying client and ignores any error while closing. */
    private void closeQuietly() {
        try { if (client != null) client.close(); }
        catch (IOException ignored) { }
    }

    /** Releases the underlying HTTP client; errors on close are ignored. */
    @Override public void close() { closeQuietly(); }
}
