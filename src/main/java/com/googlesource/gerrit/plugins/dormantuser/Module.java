package com.googlesource.gerrit.plugins.dormantuser;

import com.google.gerrit.audit.AuditListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.inject.AbstractModule;

public class Module extends AbstractModule {

    @Override
    protected void configure() {
        // Register the config and store
        bind(DormantUserConfig.class);
        bind(DormantUserStore.class);

        // Register the activity cache
        install(DormantUserCacheImpl.module());

        // Register the audit event listener
        DynamicSet.bind(binder(), AuditListener.class).to(UserAuditListener.class);
    }
}
