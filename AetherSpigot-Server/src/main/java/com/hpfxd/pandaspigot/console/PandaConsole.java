package com.hpfxd.pandaspigot.console;

import net.minecraft.server.DedicatedServer;
import net.minecrell.terminalconsole.SimpleTerminalConsole;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;

import java.nio.file.Paths;

public class PandaConsole extends SimpleTerminalConsole {
    private final DedicatedServer server;
    
    public PandaConsole(DedicatedServer server) {
        this.server = server;
    }
    
    @Override
    protected boolean isRunning() {
        return !this.server.isStopped() && this.server.isRunning();
    }
    
    @Override
    protected void runCommand(String command) {
        this.server.issueCommand(command, this.server);
    }
    
    @Override
    protected void shutdown() {
        this.server.safeShutdown();
    }
    
    @Override
    protected LineReader buildReader(LineReaderBuilder builder) {
        return super.buildReader(builder
            .appName("AetherSpigot")
            .variable(LineReader.HISTORY_FILE, Paths.get(".console_history"))
            .completer(new PandaConsoleCompleter(this.server)));
    }
}
