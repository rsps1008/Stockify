# GEMINI.md - Stockify Android App

This document provides a comprehensive overview of the Stockify Android application, its structure, and development conventions to serve as a guide for future AI-driven development.

## Project Overview

Stockify is a native Android application built with Kotlin and Jetpack Compose. Its primary purpose appears to be tracking stock holdings and transactions. The project follows modern Android development practices, including a layered architecture and dependency management with Gradle's version catalogs.

*   **Project Name:** Stockify
*   **Type:** Native Android Application
*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose with Material 3
*   **Minimum Android Version:** API 26 (Android 8.0 Oreo)
*   **Target Android Version:** API 34 (Android 14)

### Core Technologies & Libraries

*   **UI:** Jetpack Compose for declarative UI, Material 3 for components and styling.
*   **Architecture:** Likely MVVM (Model-View-ViewModel), suggested by the use of `ViewModel` and the `data`/`ui` package structure.
*   **Navigation:** Jetpack Navigation for Compose to manage screen transitions.
*   **Local Data Storage:**
    *   **Room:** For robust, structured local database storage (e.g., storing transactions and holdings).
    *   **DataStore Preferences:** For simple key-value data storage (e.g., user settings).
*   **Networking:** Ktor HTTP Client to fetch data from the internet.
*   **HTML Parsing:** Jsoup, likely used for web-scraping financial websites to get stock data.
*   **Dependency Management:** Gradle with Kotlin DSL and a centralized version catalog (`gradle/libs.versions.toml`).

### Application Structure

*   `MainActivity.kt`: The single entry-point activity that hosts the Jetpack Compose UI.
*   `StockifyApplication.kt`: A custom `Application` class, likely used for initialization tasks like setting up dependency injection or logging.
*   `ui/navigation/NavGraph.kt`: (Inferred) Contains the definition of the navigation routes and destinations for the app.
*   **Screens:** The app includes screens for:
    *   Holdings (`Screen.Holdings`)
    *   Transactions (`Screen.Transactions`)
    *   Settings (`Screen.Settings`)
    *   Add Transaction (`Screen.AddTransaction`)
*   `data/`: (Inferred) This package likely contains data sources (Repositories, DAOs, API services) and data models.

## Building and Running

The project is a standard Gradle project and can be built and run using either Android Studio or the command line.

### Command Line Instructions

**Note:** On Windows, use `gradlew.bat` instead of `./gradlew`.

*   **Build the application (Debug variant):**
    ```shell
    ./gradlew assembleDebug
    ```

*   **Run Unit Tests:**
    ```shell
    ./gradlew test
    ```

*   **Run Instrumented Tests (requires a connected device or emulator):**
    ```shell
    ./gradlew connectedAndroidTest
    ```

*   **Install on a connected device or emulator:**
    ```shell
    ./gradlew installDebug
    ```

## Development Conventions

*   **UI:** The UI is built entirely with Jetpack Compose. Follow Material 3 guidelines for new components.
*   **State Management:** ViewModels (`androidx.lifecycle.viewmodel.compose`) are used to hold and manage UI-related state.
*   **Dependencies:** All dependencies are defined in the `gradle/libs.versions.toml` file. When adding a new library, add it to the catalog first and then reference it in the `app/build.gradle.kts` file.
*   **Networking & Data:** Ktor is the client for making network requests. Room is used for the local database. Data access should be funneled through a repository layer in the `data` package (inferred convention).
*   **Code Style:** The project uses the standard Kotlin coding style. Adhere to the existing formatting.
