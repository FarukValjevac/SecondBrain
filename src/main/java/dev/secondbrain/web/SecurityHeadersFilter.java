package dev.secondbrain.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Defence in depth for every response: only this application's own scripts, styles, fonts and
 * frames may load; no other site may frame a page (so it cannot trick a click on Delete); browsers
 * must not guess content types; and referrers stay on this origin. Pages contain no inline scripts
 * or styles, so the policy needs no exceptions. A controller may still set a stricter header
 * itself, such as the original-file response.
 */
@Component
final class SecurityHeadersFilter extends OncePerRequestFilter {
    static final String POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;"
                    + " font-src 'self'; connect-src 'self'; frame-src 'self'; object-src 'none';"
                    + " base-uri 'none'; form-action 'self'; frame-ancestors 'self'";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "SAMEORIGIN");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader(
                "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        chain.doFilter(request, response);
    }
}
