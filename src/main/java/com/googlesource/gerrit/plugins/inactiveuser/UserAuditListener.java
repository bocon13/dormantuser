package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.audit.AuditEvent;
import com.google.gerrit.audit.AuditListener;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UserAuditListener implements AuditListener {
    private final Logger log = LoggerFactory.getLogger(UserAuditListener.class);

    private final AccountActivity accountActivity;

    @Inject
    UserAuditListener(AccountActivity accountActivity) {
        this.accountActivity = accountActivity;
    }

    @Override
    public void onAuditableAction(AuditEvent action) {
        log.trace("audit event: {}", action.toString());
        CurrentUser user = action.who;
        if (user != null && user.isIdentifiedUser()) {
            Account.Id id = user.getAccountId();
            log.trace("audit event for user: {}", id);
            accountActivity.markActive(id);
        }
    }
}
