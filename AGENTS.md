# Agent Instructions

This file contains repository-specific guidance for AI agents working on this project. 

## Project Overview
- **Type**: Custom Trino / Starburst Enterprise (SEP) plugin.
- **Purpose**: Integrates with the CNAM "Passeport" REST API for authentication, group provisioning, and data filtering (Row-Level Security).
- **Architecture**: 
  - Consult `ARCHITECTURE.md` for the security flow and cluster integration details.
  - Consult `CONFIGURATION.md` for plugin properties (e.g., `etc/group-provider.properties`).

## Build & Test
- **Build Tool**: Maven
- **Java Version**: **Java 25** (`pom.xml` source/target = 25, same as the Java 25 runtime of SEP 482-e). Build with a JDK 25 : `JAVA_HOME=$(brew --prefix openjdk@25) mvn clean package`. A JDK 23 (default `JAVA_HOME` on this machine) fails with class-file version errors.
- **Build Command**: `mvn clean package`
- **Test Command**: `mvn test` (Tests use JUnit 5, WireMock, and Mockito).
- **Packaging**: Uses `trino-maven-plugin` and `maven-assembly-plugin` (`src/main/assembly/plugin.xml`) to build a deployment-ready plugin directory/archive in `target/`.

## Trino SPI Conventions
- **Scope**: Trino SPI dependencies (`io.trino:trino-spi`) and OpenTelemetry must be strictly `<scope>provided</scope>` in `pom.xml`.
- **API Versions**: This project uses Trino `482`. The `io.trino.spi.*` interfaces change frequently. Do not use older Presto or Trino API conventions (e.g., legacy security contexts or function registries).
- **Extensibility**:
  - `PasseportPlugin.java`: Registers components.
  - `PasseportGroupProvider.java`: Fetches roles from the API (Fail-Closed design by default).
  - `PasseportSystemAccessControl.java`: Applies security filtering rules.
  - `PasseportFunctions.java` & `CryptoFunctions.java`: Custom SQL UDFs.

## Important Quirks
- **Fail-Closed Model**: The GroupProvider is designed to return 0 groups if the external API is unreachable, avoiding cluster crashes while blocking unauthorized access. Do not alter this design unless explicitly requested.
- **Documentation**: Trust executable configs (`pom.xml`) over prose (e.g., if `README.md` says a JDK version that differs from `pom.xml`, trust `pom.xml`).
