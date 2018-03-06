package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.httpd.AllRequestFilter;
import com.google.inject.servlet.ServletModule;

class HttpModule extends ServletModule {
  @Override
  protected void configureServlets() {
      DynamicSet.bind(binder(), AllRequestFilter.class).to(UserHttpFilter.class);
  }
}