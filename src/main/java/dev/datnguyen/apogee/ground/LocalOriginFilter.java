package dev.datnguyen.apogee.ground;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local demo: reject cross-origin browser mutations; intentionally no public deployment/auth mode.
 */
@Component
public class LocalOriginFilter extends OncePerRequestFilter {
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")) {
      String origin = request.getHeader("Origin");
      if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
        response.sendError(403);
        return;
      }
      if (origin != null) {
        try {
          URI uri = URI.create(origin);
          String host = uri.getAuthority();
          if (!host.equals(request.getHeader("Host"))) {
            response.sendError(403);
            return;
          }
        } catch (RuntimeException e) {
          response.sendError(403);
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
