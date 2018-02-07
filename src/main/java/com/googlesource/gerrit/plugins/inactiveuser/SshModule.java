package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.sshd.PluginCommandModule;

public class SshModule extends PluginCommandModule {
    @Override
    protected void configureCommands() {
        command(UserListCommand.class);
    }
}
