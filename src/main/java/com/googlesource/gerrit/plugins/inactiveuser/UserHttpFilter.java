package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.httpd.AllRequestFilter;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.CurrentUser;
import com.google.inject.Inject;
import com.google.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

@Singleton
public class UserHttpFilter extends AllRequestFilter {
    private final Logger log = LoggerFactory.getLogger(UserHttpFilter.class);

    private final Provider<CurrentUser> userProvider;

    @Inject
    UserHttpFilter(final Provider<CurrentUser> userProvider) {
        this.userProvider = userProvider;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {
        filterChain.doFilter(servletRequest, servletResponse);
        CurrentUser user = userProvider.get();
        if (user != null && user.isIdentifiedUser()) {
            Account.Id id = user.getAccountId();
            log.info("web activity for user: {}", id);
        }
    }

    @Override
    public void destroy() {}
}
