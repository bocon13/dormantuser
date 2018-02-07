package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.account.AccountByEmailCache;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.events.AssigneeChangedEvent;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.ChangeRestoredEvent;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.events.DraftPublishedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.HashtagsChangedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.RefReceivedEvent;
import com.google.gerrit.server.events.RefUpdatedEvent;
import com.google.gerrit.server.events.ReviewerDeletedEvent;
import com.google.gerrit.server.events.TopicChangedEvent;
import com.google.gerrit.server.events.VoteDeletedEvent;
import com.google.gwtorm.client.IntKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Objects;
import java.util.Optional;

/**
 * Listens for events and identifies users responsible for those events. These events are primarily driven
 * by git operations (e.g. pushing commits) or Gerrit operations (e.g. reviewing, modifying, or submitting changes).
 * The responsible user is marked as active.
 */
@Singleton
public class UserEventListener implements EventListener {
    private static final String UNKNOWN_USERID = "??";

    private final Logger log = LoggerFactory.getLogger(UserEventListener.class);

    private final AccountCache byIdCache;
    private final AccountByEmailCache byEmailCache;
    private final AccountActivity accountActivity;
    private final AllUsersName allUsersName;

    @Inject
    UserEventListener(AccountCache byIdCache,
                      AccountByEmailCache byEmailCache,
                      AccountActivity accountActivity,
                      AllUsersName allUsersName) {
        this.allUsersName = allUsersName;
        this.byIdCache = byIdCache;
        this.byEmailCache = byEmailCache;
        this.accountActivity = accountActivity;
    }

    @Override
    public void onEvent(Event event) {
        handleGerritEvent(event);
        handleGitEvent(event);
    }

    private void handleGitEvent(Event event) {
        Account user = null;
        if (event instanceof CommitReceivedEvent) {
            CommitReceivedEvent e = (CommitReceivedEvent) event;
            user = e.user.getAccount();
        } else if (event instanceof RefReceivedEvent) {
            RefReceivedEvent e = (RefReceivedEvent) event;
            user = e.user.getAccount();
        }

        if (user != null) {
            logEvent(event.getType(), user.getFullName(), user.getId().toString());
            accountActivity.markActive(user.getId());
        }
    }

    private void handleGerritEvent(Event event) {
        AccountAttribute user = null;
        if(event instanceof AssigneeChangedEvent) {
            AssigneeChangedEvent e = (AssigneeChangedEvent) event;
            user = e.changer.get();
        } else if (event instanceof ChangeAbandonedEvent) {
            ChangeAbandonedEvent e = (ChangeAbandonedEvent) event;
            user = e.abandoner.get();
        } else if (event instanceof ChangeMergedEvent) {
            ChangeMergedEvent e = (ChangeMergedEvent) event;
            user = e.submitter.get();
        } else if (event instanceof ChangeRestoredEvent) {
            ChangeRestoredEvent e = (ChangeRestoredEvent) event;
            user = e.restorer.get();
        } else if (event instanceof CommentAddedEvent) {
            CommentAddedEvent e = (CommentAddedEvent) event;
            user = e.author.get();
        } else if (event instanceof DraftPublishedEvent) {
            DraftPublishedEvent e = (DraftPublishedEvent) event;
            user = e.uploader.get();
        } else if (event instanceof HashtagsChangedEvent) {
            HashtagsChangedEvent e = (HashtagsChangedEvent) event;
            user = e.editor.get();
        } else if (event instanceof PatchSetCreatedEvent) {
            PatchSetCreatedEvent e = (PatchSetCreatedEvent) event;
            user = e.uploader.get();
        } else if (event instanceof ReviewerDeletedEvent) {
            ReviewerDeletedEvent e = (ReviewerDeletedEvent) event;
            user = e.remover.get();
        } else if (event instanceof TopicChangedEvent) {
            TopicChangedEvent e = (TopicChangedEvent) event;
            user = e.changer.get();
        } else if (event instanceof VoteDeletedEvent) {
            VoteDeletedEvent e = (VoteDeletedEvent) event;
            user = e.remover.get();
        }

        if (user != null) {
            Optional<Account.Id> id = resolveAccount(user).map(Account::getId);
            logEvent(event.getType(), user.name, id.map(IntKey::toString).orElse(UNKNOWN_USERID));
            id.ifPresent(accountActivity::markActive);
            return;
        }

        // RefUpdatedEvents could be merely updating the lastActivity timestamp, so we need to filter those
        if (event instanceof RefUpdatedEvent) {
            RefUpdatedEvent e = (RefUpdatedEvent) event;
            user = e.submitter.get();
            Optional<Account.Id> id = resolveAccount(user).map(Account::getId);
            logEvent(event.getType(), user.name, id.map(IntKey::toString).orElse(UNKNOWN_USERID));
            if (id.isPresent() && !isUserBranch(id.get(), e.getProjectNameKey(), e.getRefName())) {
                // Mark this account as active if the ref that was updated was not the user preference branch
                accountActivity.markActive(id.get());
            } else {
                log.debug("Ignoring... user missing or update is to user's preference branch");
            }
        }
    }

    private Optional<Account> resolveAccount(AccountAttribute attribute) {
        if (attribute == null) {
            return Optional.empty();
        }
        /*
            This is how the attribute is constructed:
                attribute.name = account.getFullName();
                attribute.email = account.getPreferredEmail();
                attribute.username = account.getUserName();
         */
        // First, look up the account by username
        AccountState state = byIdCache.getByUsername(attribute.username);
        if (state != null) {
            return Optional.of(state.getAccount());
        }
        // Next, look up the account by email (because username is likely not set)
        return byEmailCache.get(attribute.email).stream()
                .map(byIdCache::get)
                .filter(Objects::nonNull)
                .map(AccountState::getAccount)
                .filter(a -> a.getFullName().equals(attribute.name))
                .filter(a -> a.getPreferredEmail().equals(attribute.email))
                //TODO there could be multiple accounts, but we will just return the first one
                .findFirst();
        // Note: if there is no email or username set, this will fail to resolve the account
    }

    private boolean isUserBranch(Account.Id accountId, Project.NameKey eventProject, String eventRefName) {
        int id = accountId.get();
        String userRefName = String.format("refs/users/%02d/%d", id % 100, id);
        log.trace("Ref compare: {},{} vs. {},{}", eventProject, eventRefName, allUsersName, userRefName);
        return allUsersName.equals(eventProject) && userRefName.equals(eventRefName);
        // Note: We may also want to check the diff in the future to determine if more than the timestamp has changed
    }

    private void logEvent(String eventType, String fullName, String id) {
        log.debug("Event: {} by {} ({})", eventType, fullName, id);
    }
}
