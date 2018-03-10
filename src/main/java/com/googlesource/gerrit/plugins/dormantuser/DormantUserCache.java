package com.googlesource.gerrit.plugins.dormantuser;

import com.google.gerrit.reviewdb.client.Account;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface DormantUserCache {
    /**
     * Marks an account as active.
     * It will be considered active from now until the end of the inactivity period.
     *
     * @param id account id
     */
    void markActive(Account.Id id);

    /**
     * Returns whether an account is active or not.
     *
     * @param id account id
     * @return true if active, false otherwise
     */
    boolean isActive(Account.Id id);

    /**
     * Returns a snapshot of all users' last activity.
     *
     * @return list of users (key is account id, value is last activity timestamp)
     */
    List<Map.Entry<Account.Id, Instant>> allUsers();

    /**
     * Synchronize the cache and backing databases.
     *
     * Note: This will not populate the cache with new entries from the database.
     */
    void sync();
}
