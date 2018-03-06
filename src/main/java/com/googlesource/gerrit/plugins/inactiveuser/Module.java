package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.audit.AuditListener;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;

public class Module extends FactoryModule {

    @Override
    protected void configure() {
        // Register the lifecycle listener
        // Note: It is required to register is manually to avoid duplicate calls to start()/stop() per:
        //       https://bugs.chromium.org/p/gerrit/issues/detail?id=2106
        DynamicSet.bind(binder(), LifecycleListener.class).to(AccountActivity.class);

        // Register the audit event listener
        DynamicSet.bind(binder(), AuditListener.class).to(UserAuditListener.class);
    }
}
