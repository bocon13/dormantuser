package com.googlesource.gerrit.plugins.inactiveuser;

import com.google.gerrit.sshd.PluginCommandModule;
import com.googlesource.gerrit.plugins.inactiveuser.commands.UserListCommand;

public class SshModule extends PluginCommandModule {
    @Override
    protected void configureCommands() {
        command(UserListCommand.class);
    }
}
