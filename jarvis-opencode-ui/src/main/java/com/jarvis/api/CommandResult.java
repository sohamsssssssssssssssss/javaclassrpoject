package com.jarvis.api;

public sealed interface CommandResult permits AppLaunchReceipt, FileSearchResult, SystemSnapshot, HistoryResult {
}
