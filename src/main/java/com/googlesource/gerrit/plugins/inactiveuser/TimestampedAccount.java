package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.reviewdb.client.Account;

import java.time.Instant;

public class TimestampedAccount {
    private final Instant timestamp;
    private final Account account;

    private TimestampedAccount(Instant timestamp, Account account) {
        this.timestamp = timestamp;
        this.account = account;
    }

    public static TimestampedAccount of(Instant timestamp, Account account) {
        return new TimestampedAccount(timestamp, account);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Account getAccount() {
        return account;
    }
}