# Flac Lyric Organizer

A Kotlin-based automation tool to match, organize, rename, validate nomenclature, and analyze spectrum health (fake lossless detection) for FLAC audio files and their corresponding LRC lyric files.

---

## Architecture & SOLID Principles

This codebase is built using Clean Architecture practices and strictly adheres to the **SOLID** design principles to ensure readability, testability, and decoupled maintenance:

1. **Single Responsibility Principle (SRP)**:
   - Each handler and service has one sole purpose.
   - For example, `AudioFileHandler` is only responsible for locating audio files, `CleanTitleNomenclatureValidator` handles directory and naming validation, and `UserInterface` handles dialog rendering.
   - Decoupled process life-cycle control: The UI classes only collect inputs and display outputs; process termination and logic routing are managed by `Main.kt`.
   - Stateless services: Operational services like `FileServiceImpl` do not maintain internal execution state (`changedSet` or `errorSet`). Instead, operations return immutable `OperationResult` aggregates.

2. **Open/Closed Principle (OCP)**:
   - Behavior can be extended without modifying existing source code.
   - For example, the `AudioNomenclatureValidator` is defined as an interface, allowing new naming rules to be introduced by adding new validator classes without altering `MusicLyricsService`.
   - Configuration constants (e.g., supported extensions) are centralized in the `AudioConfig` object rather than hardcoded in multiple handlers.

3. **Liskov Substitution Principle (LSP)**:
   - Program objects should be replaceable with instances of their subtypes without altering the correctness of the program.
   - Concrete implementations like `UserInterfaceImpl` and `MatchServiceImpl` are perfectly substitutable for their interfaces `UserInterface` and `MatchService`.

4. **Interface Segregation Principle (ISP)**:
   - Client-specific interfaces prevent clients from depending on methods they do not use.
   - Granular interfaces (e.g., `FileService`, `MatchService`, `DirectoryService`) are defined so that each class depends only on the exact subset of functions it requires.

5. **Dependency Inversion Principle (DIP)**:
   - High-level modules do not depend on low-level modules; both depend on abstractions.
   - Constructor parameters of service classes require interfaces rather than concrete classes.
   - Dependencies are wired in a single composition root inside `Main.kt` (Manual Dependency Injection) and injected into services, removing compile-time coupling and permitting unit-test mocking.

---

## Performance & Efficiency Optimizations

To ensure high performance and resource efficiency when processing large volumes of media files:

1. **Buffered Chunk-based Streaming**:
   - File comparisons (`FileServiceImpl.filesAreEqual`) stream files using an 8KB buffer, performing byte-by-byte comparison on chunks. This avoids loading entire audio tracks (which can be tens or hundreds of megabytes) into memory and prevents `OutOfMemoryError` issues.

2. **Similarity Processing Cache**:
   - In `MatchServiceImpl`, audio file name attributes are lowercased and pre-mapped before calculating similarity metrics. This reduces CPU overhead by eliminating redundant string formatting in hot loops when calculating Cosine Distance over large collections.

3. **Multi-Threaded Matching**:
   - Leverages Kotlin's parallel streams to distribute name correlation across available CPU cores while ensuring thread safety on shared interfaces.

---

## Getting Started

### Prerequisites

- Java Development Kit (JDK) 17 or higher
- Gradle (provided via wrapper `gradlew` / `gradlew.bat`)

---

## Tasks & Commands

### Running the Application
To run the interactive option menu application:
```bash
# On Windows
.\gradlew.bat run

# On Linux/macOS
./gradlew run
```

### Running Unit Tests
To execute all automated unit tests:
```bash
# On Windows
.\gradlew.bat test

# On Linux/macOS
./gradlew test
```

### Generating Kotlin Documentation (Dokka)
The project uses **Dokka V2** to automatically generate documentation for all Kotlin APIs:
```bash
# On Windows
.\gradlew.bat dokkaGenerateHtml

# On Linux/macOS
./gradlew dokkaGenerateHtml
```
The generated HTML documentation will be available inside:
`build/dokka/html/index.html`
