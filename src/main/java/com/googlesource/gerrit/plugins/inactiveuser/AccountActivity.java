package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.reviewdb.client.Account;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

public interface AccountActivity {

    void markActive(Account.Id id);

    List<TimestampedAccount> allUsers();
}
