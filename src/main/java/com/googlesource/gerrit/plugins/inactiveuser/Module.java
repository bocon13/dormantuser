package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.common.EventListener;
import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.httpd.WebLoginListener;

public class Module extends FactoryModule {

    @Override
    protected void configure() {
        // Register the lifecycle listern
        // Note: It is required to register is manually to avoid duplicate calls to start()/stop() per:
        //       https://bugs.chromium.org/p/gerrit/issues/detail?id=2106
        DynamicSet.bind(binder(), LifecycleListener.class).to(AccountActivity.class);

        // Register the event listeners
        DynamicSet.bind(binder(), EventListener.class).to(UserEventListener.class);
        DynamicSet.bind(binder(), WebLoginListener.class).to(UserLoginListener.class);
    }
}
