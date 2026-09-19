package com.jarvis.api;

/**
 * The bounded set of project operations JARVIS can run. Natural language is
 * parsed into these values; the runner derives every process argument from
 * them — user text never reaches a process command line.
 */
public enum ProjectOperation {
    TEST,
    BUILD
}
