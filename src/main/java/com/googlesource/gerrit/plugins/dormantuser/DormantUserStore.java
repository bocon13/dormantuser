package com.googlesource.gerrit.plugins.dormantuser;

import com.google.common.collect.Maps;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.account.VersionedAccountPreferences;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.MetaDataUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

@Singleton
public class DormantUserStore {
    private final Logger log = LoggerFactory.getLogger(DormantUserStore.class);

    private static final String ACTIVITY_SECTION = "activity";
    private static final String ACTIVITY_NAME = "lastActivity";

    private final SchemaFactory<ReviewDb> schemaFactory;
    private final AllUsersName allUsersName;
    private final Provider<MetaDataUpdate.User> metaDataUpdateFactory;
    private final AccountCache byIdCache;
    private final IdentifiedUser.GenericFactory identifiedUserFactory;

    private final ConcurrentMap<Account.Id, Instant> lastWrite = Maps.newConcurrentMap();

    @Inject
    public DormantUserStore(SchemaFactory<ReviewDb> schemaFactory,
                            AllUsersName allUsersName,
                            Provider<MetaDataUpdate.User> metaDataUpdateFactory,
                            AccountCache byIdCache,
                            IdentifiedUser.GenericFactory identifiedUserFactory) {
        this.schemaFactory = schemaFactory;
        this.allUsersName = allUsersName;
        this.metaDataUpdateFactory = metaDataUpdateFactory;
        this.byIdCache = byIdCache;
        this.identifiedUserFactory = identifiedUserFactory;
    }

    public String getStatus(Account.Id id) {
        AccountState state = byIdCache.get(id);
        return state != null ? state.getAccount().getStatus() : null;
    }

    public void updateStatus(Account.Id id, String newStatus){
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

    public Instant updateTimestamp(final Account.Id accountId, final Instant timestamp) {
        return lastWrite.compute(accountId, (id, ts) -> {
            if (ts != null && timestamp != null && ts.compareTo(timestamp) >= 0) {
                // the stored timestamp is equal to or after the new timestamp
                return ts;
            } // else, compare the new timestamp to the git-based user preferences, and update if necessary
            IdentifiedUser user = identifiedUserFactory.create(id);
            try (MetaDataUpdate md = metaDataUpdateFactory.get().create(allUsersName, user)) {
                VersionedAccountPreferences prefs = VersionedAccountPreferences.forUser(id);
                prefs.load(md);
                Config cfg = prefs.getConfig();
                Instant lastSaved = Instant.ofEpochSecond(
                        cfg.getLong(ACTIVITY_SECTION, ACTIVITY_NAME, 0L));
                if (timestamp == null) {
                    return lastSaved;
                } else if (lastSaved.isAfter(timestamp)) {
                    return lastSaved;
                }
                cfg.setLong(ACTIVITY_SECTION, null, ACTIVITY_NAME, timestamp.getEpochSecond());
                prefs.commit(md);
                // We probably don't need to evict the cache here, but do so in case git-based preferences
                // become part of the account cache in the future.
                byIdCache.evict(id);
            } catch (IOException e) {
                log.error("Error accessing All-Users project", e);
            } catch (ConfigInvalidException e) {
                log.error("Error parsing user's config", e);
            }
            return timestamp;
        });
    }

    public Map<Account.Id, Instant> readUsersFromDisk() {
        Map<Account.Id, Instant> map = Maps.newHashMap();
        try (ReviewDb db = schemaFactory.open()) {
            for (Account a : db.accounts().all()) {
                // Read the last timestamp written
                Account.Id id = a.getId();
                Instant ts = updateTimestamp(id, null);
                map.put(id, ts);
            }
        } catch (OrmException e) {
            log.error("Reading all accounts failed", e);
        }
        return map;
    }
}
