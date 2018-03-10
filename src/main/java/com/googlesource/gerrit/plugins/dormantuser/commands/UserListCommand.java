package com.googlesource.gerrit.plugins.dormantuser.commands;

import com.google.common.base.Strings;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.extensions.annotations.RequiresCapability;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.sshd.CommandMetaData;
import com.google.gerrit.sshd.SshCommand;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.dormantuser.DormantUserCache;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
@CommandMetaData(name = "ls", description = "List users last activity")
public final class UserListCommand extends SshCommand {
    @Inject private DormantUserCache cache;
    @Inject private AccountCache byIdCache;


    @Override
    protected void run() {
        cache.allUsers().forEach(entry -> {
            Instant ts = entry.getValue().truncatedTo(ChronoUnit.SECONDS);
            Account.Id id = entry.getKey();
            Account a = byIdCache.get(id).getAccount();
            stdout.printf("%s\t%s\t%s", ts, id, a.getFullName());
            if (!Strings.isNullOrEmpty(a.getStatus())) {
                stdout.printf(" (%s)", a.getStatus());
            }
            stdout.println();
        });
    }
}

