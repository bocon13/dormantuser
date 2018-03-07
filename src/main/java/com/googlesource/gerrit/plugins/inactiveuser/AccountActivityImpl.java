package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.VersionedAccountPreferences;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gerrit.server.git.WorkQueue;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAmount;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


@Singleton
public class AccountActivityImpl implements AccountActivity, LifecycleListener {
    private static final String ACTIVITY_SECTION = "activity";
    private static final String ACTIVITY_NAME = "lastActivity";
    private static final String STATUS_EMPTY = "";
    private static final String STATUS_INACTIVE = "inactive";
    private static final TemporalAmount INACTIVE_PERIOD = Duration.ofMinutes(1);
    private static final TemporalAmount POLLING_PERIOD = Duration.ofSeconds(10);

    private final Logger log = LoggerFactory.getLogger(AccountActivityImpl.class);

    private final SchemaFactory<ReviewDb> schemaFactory;
    private final WorkQueue workQueue;
    private final AllUsersName allUsersName;
    private final Provider<MetaDataUpdate.User> metaDataUpdateFactory;
    private final AccountCache byIdCache;
    private final IdentifiedUser.GenericFactory identifiedUserFactory;

    private ConcurrentMap<Account.Id, Instant> updateMap = Maps.newConcurrentMap();
    private WorkQueue.Executor executor = null;
    private ScheduledFuture periodicFuture = null;

    @Inject
    AccountActivityImpl(SchemaFactory<ReviewDb> schemaFactory,
                        WorkQueue workQueue,
                        AllUsersName allUsersName,
                        Provider<MetaDataUpdate.User> metaDataUpdateFactory,
                        AccountCache byIdCache,
                        IdentifiedUser.GenericFactory identifiedUserFactory) {
        log.info("contructing acountactivity");
        this.schemaFactory = schemaFactory;
        this.workQueue = workQueue;
        this.allUsersName = allUsersName;
        this.metaDataUpdateFactory = metaDataUpdateFactory;
        this.byIdCache = byIdCache;
        this.identifiedUserFactory = identifiedUserFactory;
    }

    @Override
    public void start() {
        if (executor == null) {
            executor = workQueue.createQueue(1, "InactiveUser");
            long delay = POLLING_PERIOD.get(ChronoUnit.SECONDS);
            periodicFuture = executor.scheduleAtFixedRate(this::updateAllUsers, delay, delay, TimeUnit.SECONDS);
        }
        log.debug("Started");
    }

    @Override
    public void stop() {
        // Cancel the periodic deactivate task, but don't kill an active task
        if (periodicFuture != null) {
            periodicFuture.cancel(false);
            periodicFuture = null;
        }
        if (executor != null) {
            // Flush the pending timestamps to disk
            executor.submit(this::flushTimestamps);
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("Executor failed to stop", e);
            }
            executor = null;
        }
        log.debug("Stopped");
    }

    // ----------- Public / "interface" methods --------------------
    public void markActive(Account.Id id) {
        log.debug("Marking user active: {}", id);
        updateMap.put(id, Instant.now());
        AccountState account = byIdCache.get(id);
        if (account != null) {
            activateUser(account.getAccount());
        }
    }

    public List<TimestampedAccount> allUsers() {
        List<TimestampedAccount> users = updateAllUsers(true);
        users.sort(Comparator.comparing(TimestampedAccount::getTimestamp).reversed());
        return users;
    }

    // ----------- Private / implementation methods --------------------
    private void activateUser(Account account) {
        if (account != null && STATUS_INACTIVE.equals(account.getStatus())) {
            // Clear the user's inactive status
            updateStatus(account.getId(), STATUS_EMPTY);
            log.info("Active user: {} ({})", account.getFullName(), account.getId());
        }
    }

    private void deactivateUser(Account account) {
        if (account != null && !STATUS_INACTIVE.equals(account.getStatus())) {
            // Set the user's status to inactive
            updateStatus(account.getId(), STATUS_INACTIVE);
            log.info("Inactive user: {} ({})", account.getFullName(), account.getId());
        }
    }

    private void updateStatus(Account.Id id, String newStatus){
        try (ReviewDb db = schemaFactory.open()) {
            Account result = db.accounts().atomicUpdate(
                    id,
                    a -> {
                        a.setStatus(newStatus);
                        return a;
                    });
            if (result != null) {
                byIdCache.evict(result.getId());
            }
        } catch (OrmException e) {
            log.error("Database update failed", e);
        } catch (IOException e) {
            log.error("Cache eviction failed", e);
        }
    }

    private Instant writeLatestTimestamp(Account.Id id) {
        Instant lastCached = updateMap.get(id);
        IdentifiedUser user = identifiedUserFactory.create(id);
        try (MetaDataUpdate md = metaDataUpdateFactory.get().create(allUsersName, user)) {
            VersionedAccountPreferences prefs = VersionedAccountPreferences.forUser(id);
            prefs.load(md);
            Config cfg = prefs.getConfig();
            Instant lastSaved = Instant.ofEpochSecond(
                    cfg.getLong(ACTIVITY_SECTION, ACTIVITY_NAME, 0L));
            if (lastCached == null) {
                return lastSaved;
            } else if (lastSaved.isAfter(lastCached)) {
                // clear the stale timestamp from the in-memory map
                updateMap.remove(id, lastCached);
                return lastSaved;
            }
            cfg.setLong(ACTIVITY_SECTION, null, ACTIVITY_NAME, lastCached.getEpochSecond());
            prefs.commit(md);
            // TODO we probably don't need to evict the cache here
            byIdCache.evict(id);
        } catch (IOException e) {
            log.error("Error accessing All-Users project", e);
            return lastCached;
        } catch (ConfigInvalidException e) {
            log.error("Error parsing user's config", e);
            return lastCached;
        }
        // timestamp has been writen to user preferences, clear it from the in-memory map
        updateMap.remove(id, lastCached);
        return lastCached;
    }

    private Instant updateUser(Account a, Instant inactiveWindow) {
        Instant lastUpdate = writeLatestTimestamp(a.getId());
        log.trace("{} was last active at {}", a.getFullName(), lastUpdate);
        if (lastUpdate.isBefore(inactiveWindow) &&
                // check if the user registered within the inactivity window
                a.getRegisteredOn().toInstant().isBefore(inactiveWindow)) {
            deactivateUser(a);
        } else {
            //This should always be a no-op because it is done immediately
            activateUser(a);
        }
        return lastUpdate;
    }

    private void updateAllUsers() {
        updateAllUsers(false);
    }

    private List<TimestampedAccount> updateAllUsers(boolean withReturn) {
        List<TimestampedAccount> users = withReturn ? Lists.newArrayList() : null;
        Instant inactiveWindow = Instant.now().minus(INACTIVE_PERIOD);
        try {
            log.trace("Updating all users...");
            try (ReviewDb db = schemaFactory.open()) {
                for (Account a : db.accounts().all()) {
                    Instant lastUpdate = updateUser(a, inactiveWindow);
                    if (withReturn) {
                        users.add(TimestampedAccount.of(lastUpdate, a));
                    }
                }
            } catch (OrmException e) {
                log.error("Reading all accounts failed", e);
            }
        } catch (Exception e) {
            log.error("Unexpected exception", e);
        }
        return users;
    }

    private void flushTimestamps() {
        updateMap.forEach((id, ts) -> writeLatestTimestamp(id));
    }
}
