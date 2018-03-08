package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.audit.AuditListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.inject.AbstractModule;

public class Module extends AbstractModule {

    @Override
    protected void configure() {
        // Register the config
        bind(InactiveUserConfig.class);

        // Register the lifecycle listener
        // Note: It is required to register is manually to avoid duplicate calls to start()/stop() per:
        //       https://bugs.chromium.org/p/gerrit/issues/detail?id=2106
        DynamicSet.bind(binder(), LifecycleListener.class).to(AccountActivityImpl.class);

        // Register the account activity implementation
        bind(AccountActivity.class).to(AccountActivityImpl.class);

        // Register the audit event listener
        DynamicSet.bind(binder(), AuditListener.class).to(UserAuditListener.class);
    }
}
