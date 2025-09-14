# Intelligent Raw Message Processor Utility

## Project Overview

This Java-based utility is designed for generating, processing, and analyzing raw financial messages in the context of sanctions screening and compliance. It integrates with watchlists (e.g., OFAC, EU, UN, HMT) to create message variants, post them to a REST service for matching, and analyze responses for true positives/negatives. Key features include:

- **Message Generation**: Creates raw messages with variations (e.g., Character Edit Distance - CED, stopwords, synonyms) from database watchlists.
- **Message Processing**: Posts messages to a sanctions screening API and records responses.
- **Response Analysis**: Evaluates matches against expected criteria, marking tests as PASS/FAIL.
- **Matching Engine Toggling**: Switches between Open Search (OS) and Oracle Text (OT) engines with cache refresh.
- **Concurrency Support**: Uses multi-threading for efficient processing and analysis.
- **Output**: Generates Excel files with detailed results for easy review.

The tool is useful for testing and validating sanctions screening systems, ensuring compliance with financial regulations.

## Architecture

The utility follows a modular flow:

1. **Configuration Loading**: Reads from `config.properties` and `source.json`.
2. **Raw Message Generation**: Queries database, generates variants, and writes to Excel.
3. **Processing**: Posts messages to API, handles retries, and updates Excel with responses.
4. **Analysis**: Fetches feedback from database, analyzes matches, and updates results.
5. **Optional Toggling**: Switches matching engines and refreshes caches.

### Flow Diagram

```mermaid
graph TD
    A[Config (config.properties, source.json)] --> B[Main]
    B --> C[RawMessageGenerator]
    C --> D[Generate Excel Files with Variants (CED, Stopwords, Synonyms)]
    D --> E[MessageProcessingUtility]
    E --> F[Post Messages to REST Service]
    F --> G[Store Responses in Excel]
    B --> H[ToggleMatchingEngine (Optional)]
    H --> I[Switch Engine & Refresh Cache]
    G --> J[MessageResponseAnalyzer]
    J --> K[Analyze Matches & Update Excel (Pass/Fail)]
    L[Database (Watchlists, Feedback)] <--> C
    L <--> J
    M[External REST API] <--> E
    M <--> H
```

## Prerequisites

- **Java**: JDK 8 or higher.
- **Database**: Oracle Database with access to watchlist tables (e.g., `FCC_TF_DIM_COUNTRY`, `FCC_WL_OFAC`).
- **Libraries**: 
  - Oracle JDBC drivers (included in `External Libraries/ojdbc17-full`).
  - Apache POI for Excel handling.
  - SLF4J and Logback for logging.
  - JSON.org or similar for JSON processing.
  - Other dependencies in `External Libraries/TFCS Libs` (e.g., Jackson, OpenCSV).
- **Configuration Files**: `config.properties` and `source.json` in `utility/intelligent-raw-message-processor-utility/bin/`.
- **Wallet**: Oracle wallet for secure DB connections (e.g., `wallet_zip_extracted_file`).

Ensure external APIs (e.g., token and posting endpoints) are accessible.

## Installation and Setup

1. **Configure Environment**:
   - Update `config.properties` with DB details, API endpoints, and processing flags.
   - Prepare `source.json` with raw message templates (e.g., containing `__TOKEN__` placeholders).
2. **Run Script**: Use `run.bat` in `utility/intelligent-raw-message-processor-utility/bin/` to execute.

## Configuration Guide

Configuration is primarily in `config.properties`:

- **Modules**: Enable/disable toggleMatchingEngine (e.g., `toggleMatchingEngine=Y`).
- **Database**: `jdbcdriver`, `jdbcurl`, `walletName`.
- **Message Settings**: `tagName`, `webServiceId` (1=NameAndAddress, 2=Identifier, etc.), `watchListType` (e.g., OFAC).
- **Variants**: `ced1=Y` for 1-char edits, `stopword=Y`, `synonym=Y`.
- **API**: `msgPosting.tokenUrl`, `msgPosting.client.id`, retry settings.
- **Filters**: `whereClause` for DB queries.

`source.json` defines the base message structure with placeholders like `__TOKEN__` and `__IDENTIFIER__`.

For details, see the file comments.

## Usage Instructions

1. **Prepare Config**: Edit `config.properties` and `source.json`.
2. **Run the Utility**:
   - Execute `run.bat` or `java -jar intelligent-raw-message-processor.jar`.
   - The tool logs to `out/log/` and generates Excel in `out/`.
3. **Process Flow**:
   - Generates raw messages in Excel chunks (split by row limit).
   - Prompts to proceed with processing.
   - Posts messages, analyzes responses, and updates Excel with PASS/FAIL.
   - If toggling enabled, switches engines and re-processes.
4. **Output**: Excel files like `executing_1.xlsx` with columns for inputs, responses, and test status.

Example: To test OFAC name matching with 1 CED, set `watchListType=OFAC`, `ced1=Y`, `webServiceId=1`.

## Key Classes and Components

- **Main.java**: Entry point; orchestrates generation, processing, analysis, and toggling.
- **Constants.java**: Defines mappings (watchlists, web services), file paths, and constants.
- **RawMessageGenerator.java**: Queries DB, generates variants (CED, stopwords, synonyms), writes to Excel/JSON.
- **MessageProcessingUtility.java**: Posts messages to API, handles retries, updates Excel with tokens/matches.
- **MessageResponseAnalyzer.java**: Fetches feedback, analyzes matches, marks PASS/FAIL in Excel.
- **ToggleMatchingEngine.java**: Switches between OS/OT, refreshes caches via API.
- **SQLUtility.java**: Manages DB connections using Oracle wallet.
- **AnalyzerRunnable.java** and **ProcessorRunnable.java**: Threaded workers for concurrent processing/analysis.
- **SourceInputModel.java**: Model for raw message structure.
- **logback.xml**: Logging configuration.

## Troubleshooting

- **DB Connection Issues**: Verify wallet path and JDBC URL. Check logs for SQL errors.
- **API Errors**: Ensure token/client credentials are correct; check retry settings for timeouts.
- **No Matches**: Confirm `tagName`, `webServiceId`, and variants match expectations.
- **Performance**: Adjust thread counts (`processor_thread_count`, `analyzer_thread_count`) for large datasets.
- **Logs**: Check `out/log/` for detailed errors (e.g., UtilityMain.log).

If issues persist, enable debug logging in logback.xml.

## Contributing/Extending

- Add new watchlist types to `Constants.TABLE_WL_MAP`.
- Extend variant generation in `RawMessageGenerator` for custom rules.
- Contributions welcome; fork and submit pull requests.

For questions, contact the maintainer.
