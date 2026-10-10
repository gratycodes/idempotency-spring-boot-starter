package io.github.gratycodes.idempotency.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads the request body once, up front, so it can be fingerprinted before the handler runs and
 * still be read by the handler afterwards. Form posts are left to the container (which parses them
 * into parameters) and fingerprinted from the sorted parameter map instead.
 */
public class BufferedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    public BufferedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = isForm(request.getContentType()) ? null : request.getInputStream().readAllBytes();
    }

    /** Bytes that identify the body for fingerprinting. */
    public byte[] fingerprintBody() {
        if (body != null) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String[]> e : new TreeMap<>(getParameterMap()).entrySet()) {
            for (String v : e.getValue()) {
                sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                        .append(URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (body == null) {
            return super.getInputStream();
        }
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return in.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Async reads are not supported on idempotent requests");
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (body == null) {
            return super.getReader();
        }
        String enc = getCharacterEncoding();
        Charset charset = enc == null ? StandardCharsets.UTF_8 : Charset.forName(enc);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public int getContentLength() {
        return body == null ? super.getContentLength() : body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body == null ? super.getContentLengthLong() : body.length;
    }

    static boolean isForm(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }
}
