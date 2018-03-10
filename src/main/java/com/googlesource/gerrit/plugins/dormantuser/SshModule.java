package com.googlesource.gerrit.plugins.dormantuser;

import com.google.gerrit.sshd.PluginCommandModule;
import com.googlesource.gerrit.plugins.dormantuser.commands.LogTailCommand;
import com.googlesource.gerrit.plugins.dormantuser.commands.UserListCommand;

public class SshModule extends PluginCommandModule {
    @Override
    protected void configureCommands() {
        command(UserListCommand.class);
    }
}
