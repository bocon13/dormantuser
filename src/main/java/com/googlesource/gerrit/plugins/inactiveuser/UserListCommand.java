package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.common.base.Strings;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name = "ls", description = "List users last activity")
public final class UserListCommand extends SshCommand {
    @Inject
    private AccountActivity accountActivity;

    @Override
    protected void run() {
        accountActivity.allUsers().forEach(tsa -> {
            Instant ts = tsa.getTimestamp().truncatedTo(ChronoUnit.SECONDS);
            Account a = tsa.getAccount();
            stdout.printf("%s\t%s\t%s", ts, a.getId(), a.getFullName());
            if (!Strings.isNullOrEmpty(a.getStatus())) {
                stdout.printf(" (%s)", a.getStatus());
            }
            stdout.println();
        });
    }
}

