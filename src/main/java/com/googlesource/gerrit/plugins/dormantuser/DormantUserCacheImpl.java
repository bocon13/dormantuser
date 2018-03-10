package com.googlesource.gerrit.plugins.dormantuser;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Singleton
public class DormantUserCacheImpl implements DormantUserCache {
    private final Logger log = LoggerFactory.getLogger(DormantUserCacheImpl.class);


    private final DormantUserStore store;
    private final DormantUserConfig config;

    private final Map<Account.Id, Instant> timestampCache;
    private final Set<Account.Id> activeUsers;

    @Inject
    public DormantUserCacheImpl(DormantUserStore store,
                                DormantUserConfig config) {
        this.store = store;
        this.config = config;
        this.timestampCache = store.readUsersFromDisk();
        this.activeUsers = Sets.newHashSet();
    }

    @Override
    public void markActive(Account.Id id) {
        timestampCache.put(id, Instant.now());
        activateUser(id);
    }

    @Override
    public boolean isActive(Account.Id id) {
        Instant dormantWindow = Instant.now().minus(config.getDormantPeriod());
        Instant lastActive = timestampCache.get(id);
        if (lastActive == null) {
            return false;
        } else if (lastActive.isAfter(dormantWindow)) {
            // User has been active within the dormancy period
            return true;
        } else if (config.getEpoch().isAfter(dormantWindow)) {
            // Plugin was first activated within the dormancy period
            return true;
        }
        return false;
    }

    @Override
    public List<Map.Entry<Account.Id, Instant>> allUsers() {
        List<Map.Entry<Account.Id, Instant>> users = Lists.newArrayList(timestampCache.entrySet());
        users.sort(Map.Entry.<Account.Id, Instant>comparingByValue().reversed());
        return users;
    }

    @Override
    public void sync() {
        log.debug("Starting sync...");
        // Update all timestamps in cache
        timestampCache.replaceAll(store::updateTimestamp);
        // Check for status changes
        timestampCache.keySet().forEach(id -> {
            if (isActive(id)) {
                activateUser(id);
            } else {
                deactivateUser(id);
            }
        });
        log.debug("Finished sync.");
    }

    private void activateUser(Account.Id id) {
        if (activeUsers.contains(id)) {
            log.trace("User {} is already active", id);
            return;
        }
        activeUsers.add(id);
        if (config.getDormantUserStatus().equals(store.getStatus(id))) {
            // Clear the user's dormant status
            store.updateStatus(id, config.getDefaultUserStatus());
            log.debug("Marking user {} as active", id);
        }
    }

    private void deactivateUser(Account.Id id) {
        activeUsers.remove(id);
        if (!config.getDormantUserStatus().equals(store.getStatus(id))) {
            // Set the user's status to dormant
            store.updateStatus(id, config.getDormantUserStatus());
            log.debug("Marking user {} as dormant", id);
        }
    }

    /**
     * Periodically syncs the cache to the git-backed storage.
     */
    @Singleton
    static class Lifecycle implements LifecycleListener {
        private final Logger log = LoggerFactory.getLogger(Lifecycle.class);

        private static final String QUEUE_NAME = "DormantUser";

        private final DormantUserConfig config;
        private final WorkQueue queue;
        private final Runnable sync;
        private ScheduledFuture periodicFuture;

        @Inject
        Lifecycle(DormantUserConfig config,
                  DormantUserCache cache,
                  WorkQueue queue) {
            this.config = config;
            this.queue = queue;
            this.sync = new Synchronizer(cache);
        }

        @Override
        public void start() {
            long delay = config.getPollingPeriod().get(ChronoUnit.SECONDS);
            WorkQueue.Executor executor = queue.getDefaultQueue();
            periodicFuture = executor.scheduleAtFixedRate(sync, delay, delay, TimeUnit.SECONDS);
        }

        @Override
        public void stop() {
            // Cancel the periodic background task, but don't kill an active task
            if (periodicFuture != null) {
                log.error("cancel: {}", periodicFuture.cancel(false));
                periodicFuture = null;
            }
            queue.getDefaultQueue().submit(sync);
        }
    }

    static class Synchronizer implements Runnable {
        private final Logger log = LoggerFactory.getLogger(Synchronizer.class);

        private final DormantUserCache cache;

        Synchronizer(DormantUserCache cache) {
            this.cache = cache;
        }

        @Override
        public void run() {
            try {
                cache.sync();
            } catch(Exception e) {
                log.error("Uncaught exception:", e);
            }
        }

        @Override
        public String toString() {
            return "Dormant User Synchronizer";
        }
    }

    static Module module() {
        return new LifecycleModule() {
            @Override
            protected void configure() {
                // Register the cache implementation
                bind(DormantUserCache.class).to(DormantUserCacheImpl.class);
                // Register the periodic sync listener
                listener().to(Lifecycle.class);
            }
        };
    }
}
