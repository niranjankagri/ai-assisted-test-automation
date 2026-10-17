package com.niranjankagri.aitesting;

import com.niranjankagri.aitesting.app.LeaveDeskServer;

/**
 * One entry point for everything you run by hand:
 * <pre>
 *   mvn -q exec:java -Dexec.args="app [port] [--bug]"   start LeaveDesk (default port 8080)
 * </pre>
 */
public final class Cli {

    private Cli() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "help" : args[0];
        switch (command) {
            case "app" -> {
                int port = args.length > 1 && args[1].matches("\\d+") ? Integer.parseInt(args[1]) : 8080;
                boolean bug = java.util.Arrays.asList(args).contains("--bug");
                LeaveDeskServer.runForever(port, bug);
            }
            default -> System.out.println("""
                    Commands:
                      app [port] [--bug]   start LeaveDesk (default port 8080; --bug plants the overlap bug)""");
        }
    }
}
