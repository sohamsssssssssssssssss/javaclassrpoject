# JARVIS

College desktop-assistant project by Soham and Ved. Sprint 1 covers typed commands for configured application launching, bounded PDF filename search, system status and persisted history.

## Requirements

- JDK 21
- Maven 3.9+
- macOS 11+, Windows, or Linux with GTK 3 for JavaFX

## Commands

```sh
mvn test
mvn javafx:run
```

Stage A intentionally exposes no working commands. It only freezes contracts so the core, UI and service branches can be implemented independently.
