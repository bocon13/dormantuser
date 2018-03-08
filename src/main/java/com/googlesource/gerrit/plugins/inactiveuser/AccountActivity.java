package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.reviewdb.client.Account;

import java.util.List;

public interface AccountActivity {

    void markActive(Account.Id id);

    boolean isActive(Account.Id id);

    List<TimestampedAccount> allUsers();
}
