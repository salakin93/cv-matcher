package com.cvmatcher.cv_matcher_backend.identity.insfrastructure.security;

import com.cvmatcher.cv_matcher_backend.identity.application.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class BearerJwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final JdbcTemplate jdbc;

    public BearerJwtAuthenticationFilter(JwtService jwt, JdbcTemplate jdbc) {
        this.jwt = jwt;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) try {
            var claims = jwt.verify(header.substring(7));
            var userId = UUID.fromString((String) claims.get("sub"));
            var sessionId = UUID.fromString((String) claims.get("sid"));
            var state = jdbc.query(
                    "select u.role,u.status,u.password_change_required from user_account u join user_session s on s.user_id=u.id where u.id=? and s.id=? and s.revoked_at is null and s.expires_at > current_timestamp",
                    rs -> rs.next() ? new Object[]{rs.getString(1), rs.getString(2), rs.getBoolean(3)} : null,
                    userId,
                    sessionId
            );
            if (state != null && "ACTIVE".equals(state[1])) {
                var role = Boolean.TRUE.equals(state[2]) ? "PASSWORD_CHANGE_REQUIRED" : (String) state[0];
                var authentication = new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (Exception ignored) {
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
