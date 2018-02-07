package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.httpd.WebLoginListener;
import com.google.gerrit.server.IdentifiedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Listens for user web login events and marks users active upon login.
 */
@Listen
@Singleton
public class UserLoginListener implements WebLoginListener {
    private final Logger log = LoggerFactory.getLogger(UserLoginListener.class);

    private final AccountActivity accountActivity;

    @Inject
    UserLoginListener(AccountActivity accountActivity) {
        this.accountActivity = accountActivity;
    }

    @Override
    public void onLogin(IdentifiedUser userId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        log.trace("User Login: {} ({})", userId.getAccount().getFullName(), userId.getAccountId());
        accountActivity.markActive(userId.getAccountId());
    }

    @Override
    public void onLogout(IdentifiedUser userId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // nothing to do :)
    }
}
