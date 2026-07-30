package com.raaspal.robotrecommendation.partner.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

/**
 * Resolves the caller's IP address from behind Render's TLS-terminating proxy.
 *
 * <p><strong>Why the last value and not the first.</strong> {@code X-Forwarded-For}
 * is a comma-separated trail, {@code "client, proxy1, proxy2"}, and each proxy
 * <em>appends</em> the address it received the connection from. The leftmost entry
 * is therefore whatever the original caller claimed — it is request data, freely
 * settable by anyone. A client sending {@code X-Forwarded-For: 1.2.3.4} to a
 * Render-hosted app produces {@code "1.2.3.4, <real client>"}, so reading the left
 * gives the forgery and reading the right gives the address Render actually saw.
 *
 * <p>The rightmost entry is correct or better under every deployment this service
 * has: with one proxy in front it is the true peer address, and with a proxy that
 * replaces rather than appends the header there is only one value anyway. It would
 * only be wrong behind <em>two</em> trusted proxies (say Cloudflare in front of
 * Render), where the true client sits one further left — revisit this if such a
 * layer is ever added.
 *
 * <p>This matters beyond tidy audit rows: {@link PartnerRateLimitFilter} meters
 * unauthenticated token requests per IP, so a spoofable address is a throttle an
 * attacker can sidestep by varying a header.
 */
final class ClientIpResolver {

    private ClientIpResolver() {
    }

    static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int lastComma = forwarded.lastIndexOf(',');
            String last = lastComma >= 0 ? forwarded.substring(lastComma + 1) : forwarded;
            if (StringUtils.hasText(last)) {
                return last.trim();
            }
        }
        // No proxy header: a direct connection, e.g. local development.
        return request.getRemoteAddr();
    }
}
