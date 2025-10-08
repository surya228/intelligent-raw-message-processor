package com.oracle.ofss.sanctions.tf.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Main {
    /**
     * Static initializer to set up logging directory and clear existing log files.
     */
    static {
        initializeLogging();
    }

    /**
     * Initializes the logging system by creating the log directory and clearing existing log files.
     */
    private static void initializeLogging() {
        try {
            File logDir = new File(Constants.OUTPUT_FOLDER, "log");
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    System.err.println("Could not create log directory at " + logDir.getAbsolutePath());
                }
            }

            // Truncate existing log files on startup to ensure a fresh run
            clearExistingLogFiles(logDir);
            System.setProperty("log.dir", logDir.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("Failed to initialize log directory: " + t.getMessage());
        }
    }

    /**
     * Clears existing log files in the specified directory.
     *
     * @param logDir The directory containing log files to clear
     */
    private static void clearExistingLogFiles(File logDir) {
        try {
            String[] logFiles = {
                    "UtilityMain.log",
                    "RawMessageGenrator.log",
                    "MessageProcessor.log",
                    "MessageAnalyzer.log"
            };

            for (String logFile : logFiles) {
                File file = new File(logDir, logFile);
                if (file.exists()) {
                    try {
                        new java.io.FileOutputStream(file, false).close();
                    } catch (Throwable clearEx) {
                        // Fallback to delete if truncation fails
                        if (!file.delete()) {
                            System.err.println("Failed to clear log file: " + file.getAbsolutePath() +
                                             " due to: " + clearEx.getMessage());
                        }
                    }
                }
            }
        } catch (Throwable t2) {
            System.err.println("Failed while clearing previous log files: " + t2.getMessage());
        }
    }

    /**
     * Cleans up temporary files from previous runs at application startup.
     * Deletes generated_file_count files and executing Excel files.
     */
    private static void cleanupTemporaryFiles() {
        logger.info("=============================================================");
        logger.info("                CLEANING UP TEMPORARY FILES                 ");
        logger.info("=============================================================");

        try {
            // Clean up generated_file_count files
            File[] countFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) ->
                name.matches("generated_file_count.*\\.txt"));
            if (countFiles != null) {
                for (File file : countFiles) {
                    if (file.delete()) {
                        logger.info("Cleaned up temporary count file: {}", file.getName());
                    } else {
                        logger.warn("Failed to delete temporary count file: {}", file.getName());
                    }
                }
            }

            // Clean up executing Excel files
            File[] excelFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) ->
                name.matches("executing.*\\.xlsx"));
            if (excelFiles != null) {
                for (File file : excelFiles) {
                    if (file.delete()) {
                        logger.info("Cleaned up temporary Excel file: {}", file.getName());
                    } else {
                        logger.warn("Failed to delete temporary Excel file: {}", file.getName());
                    }
                }
            }

            logger.info("Temporary file cleanup completed");
        } catch (Throwable t) {
            logger.error("Error during temporary file cleanup: {}", t.getMessage());
            // Don't fail the application if cleanup has issues
        }
    }

    /**
     * Cleans up remaining individual count files after run_details.json is created.
     */
    private static void cleanupIndividualCountFiles() {
        logger.info("Cleaning up remaining individual count files...");

        try {
            File[] countFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) ->
                name.matches("generated_file_count.*\\.txt"));
            if (countFiles != null) {
                for (File file : countFiles) {
                    if (file.delete()) {
                        logger.info("Cleaned up individual count file: {}", file.getName());
                    } else {
                        logger.warn("Failed to delete individual count file: {}", file.getName());
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Error during individual count file cleanup: {}", t.getMessage());
        }
    }
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Main entry point for the Intelligent Raw Message Processor Utility.
     * Processes multiple configuration files with optional matching engine toggling.
     *
     * @param args Command line arguments (not used in current implementation)
     * @throws Exception If any critical error occurs during processing
     */
    public static void main(String[] args) throws Exception {
        logger.info("=============================================================");
        logger.info("      INTELLIGENT RAW MESSAGE PROCESSOR UTILITY STARTED      ");
        logger.info("=============================================================");

        Date startTimestamp = new Date();
        SimpleDateFormat dateFormatter = new SimpleDateFormat(Constants.DATE_SUFFIX_FORMAT);
        SimpleDateFormat timeFormatter = new SimpleDateFormat(Constants.TIME_SUFFIX_FORMAT);
        String formattedStartDate = dateFormatter.format(startTimestamp);
        String formattedStartTime = timeFormatter.format(startTimestamp);

        long executionStartMillis = System.currentTimeMillis();

        // Clean up temporary files from previous runs
        cleanupTemporaryFiles();

        // Load common properties
        Properties commonProps = loadProperties(Constants.COMMON_CONFIG_FILE_PATH);
        logger.info("Common properties loaded");

        // Get all specific config files
        List<File> allSpecificConfigs = getSpecificConfigFiles();

        // Filter enabled configs based on common.properties
        List<File> enabledConfigs = filterEnabledConfigs(allSpecificConfigs, commonProps);

        if (enabledConfigs.isEmpty()) {
            logger.info("No enabled configs found. Exiting utility.");
            System.exit(0);
        }

        boolean isToggle = Constants.YES.equalsIgnoreCase(commonProps.getProperty(Constants.TOGGLE_MATCHING_ENGINE));

        ToggleMatchingEngine toggleMatchingEngine = new ToggleMatchingEngine();
        String matchingEngine = toggleMatchingEngine.findCurrentMatchingEngine();
        logger.info("Initial Matching Engine: {}", matchingEngine);

        // Process all enabled configs with current matching engine
        logger.info("=============================================================");
        logger.info("Processing {} enabled configs with matching engine: {}", enabledConfigs.size(), matchingEngine);
        logger.info("=============================================================");

        // Create a list to store config counts
        JSONArray runDetails = new JSONArray();

        for (File specificConfig : enabledConfigs) {
            try {
                String configName = specificConfig.getName().replace(".properties", "");
                Properties specificProps = loadProperties(specificConfig.getPath());

                if (!isAtLeastOneVariantEnabled(specificProps)) {
                    logger.warn("Skipping config {}: at least one of ced0,ced1,ced2,ced3,synonym,stopword must be enabled.", configName);
                    continue;
                }

                Properties mergedProps = mergeProperties(commonProps, specificProps);

                int generatedCount = processSingleConfig(mergedProps, configName, matchingEngine, isToggle, false, formattedStartDate, formattedStartTime, false);

                // Get file count
                int fileCount = 1;
                try {
                    String countFileName = Constants.OUTPUT_FILE_COUNT_PATH.replace(".txt", "_" + configName + ".txt");
                    File countFile = new File(Constants.OUTPUT_FOLDER, countFileName);
                    if (countFile.exists()) {
                        String countStr = new String(Files.readAllBytes(countFile.toPath())).trim();
                        fileCount = Integer.parseInt(countStr);
                    }
                } catch (Exception e) {
                    logger.error("Error reading file count for config {}: {}", configName, e.getMessage());
                }

                // Add to run details
                JSONObject configDetail = new JSONObject();
                configDetail.put("configName", configName);
                configDetail.put("fileNo", fileCount);
                configDetail.put("rawMessageCount", generatedCount);
                runDetails.put(configDetail);

                logger.info("Collected counts for config {}: files={}, rawMessages={}", configName, fileCount, generatedCount);
            } catch (Exception e) {
                logger.error("Error processing config {}: {}", specificConfig.getName(), e.getMessage());
                // Continue with other configs
            }
        }

        logger.info("All configs processed with matching engine: {}", matchingEngine);

        // Handle toggle if enabled
        if (isToggle) {
            logger.info("=============================================================");
            logger.info("Toggling Matching Engine...");
            logger.info("=============================================================");
            matchingEngine = toggleMatchingEngine.toggleMatchingEngine();
            logger.info("Matching engine toggled to: {}", matchingEngine);

            // Process all enabled configs again with new matching engine
            logger.info("=============================================================");
            logger.info("Re-processing {} enabled configs with new matching engine: {}", enabledConfigs.size(), matchingEngine);
            logger.info("=============================================================");

            for (File specificConfig : enabledConfigs) {
                try {
                    String configName = specificConfig.getName().replace(".properties", "");
                    Properties specificProps = loadProperties(specificConfig.getPath());

                    if (!isAtLeastOneVariantEnabled(specificProps)) {
                        logger.warn("Skipping config {}: at least one of ced0,ced1,ced2,ced3,synonym,stopword must be enabled.", configName);
                        continue;
                    }

                    Properties mergedProps = mergeProperties(commonProps, specificProps);

                    processSingleConfig(mergedProps, configName, matchingEngine, isToggle, true, formattedStartDate, formattedStartTime, true);
                } catch (Exception e) {
                    logger.error("Error re-processing config {}: {}", specificConfig.getName(), e.getMessage());
                    // Continue with other configs
                }
            }

            logger.info("All configs re-processed with matching engine: {}", matchingEngine);
        }

        // Write run details to JSON file
        if (runDetails.length()!=0) {
            File runDetailsFile = new File(Constants.OUTPUT_FOLDER, Constants.RUN_DETAILS_FILE_NAME);
            try (FileWriter fileWriter = new FileWriter(runDetailsFile)) {
                fileWriter.write(runDetails.toString(4)); // Pretty print with 4-space indentation
                logger.info("Run details written to: {}", runDetailsFile.getAbsolutePath());

                // Clean up individual count files after JSON is successfully written
                cleanupIndividualCountFiles();
            } catch (IOException e) {
                logger.error("Error writing run details to {}: {}", runDetailsFile.getAbsolutePath(), e.getMessage());
            }
        } else {
            logger.info("No run details to write (no configs processed successfully)");
        }

        logger.info("=============================================================");
        logger.info("     INTELLIGENT RAW MESSAGE PROCESSOR UTILITY COMPLETED     ");
        logger.info("=============================================================");
        long executionEndMillis = System.currentTimeMillis();
        logger.info("Total time taken by utility: {} seconds", (executionEndMillis - executionStartMillis) / 1000L);
    }



    /**
     * Renames output files by appending a timestamp and webservice name.
     * @param props Properties containing configuration details like webservice name.
     */
    private static void runProcessing(String matchingEngine, List<File> excelFiles, Properties props, boolean isToggle, boolean isFinalRun, String renamePrefix, String startDate, String startTimeStr, String configName) throws Exception {
        int processorQueueThreads = Integer.parseInt(props.getProperty(Constants.PROCESSOR_QUEUE_THREADS, String.valueOf(Constants.DEFAULT_QUEUE_THREAD_COUNT)));
        int analyzerQueueThreads = Integer.parseInt(props.getProperty(Constants.ANALYZER_QUEUE_THREADS, String.valueOf(Constants.DEFAULT_QUEUE_THREAD_COUNT)));

        BlockingQueue<File> processorQueue = new LinkedBlockingQueue<>();
        BlockingQueue<File> analyzerQueue = new LinkedBlockingQueue<>();

        ExecutorService processorPool = Executors.newFixedThreadPool(processorQueueThreads);
        ExecutorService analyzerPool = Executors.newFixedThreadPool(analyzerQueueThreads);

        for (int i = 0; i < processorQueueThreads; i++) {
            processorPool.submit(new ProcessorRunnable(processorQueue, analyzerQueue, matchingEngine, props));
        }
        for (int i = 0; i < analyzerQueueThreads; i++) {
            analyzerPool.submit(new AnalyzerRunnable(analyzerQueue, matchingEngine, isToggle, isFinalRun, renamePrefix, startDate, startTimeStr, props));
        }

        // Add existing files to queue for processing
        for (File file : excelFiles) {
            processorQueue.put(file);
        }

        // Put one poison pill per processor thread
        for (int i = 0; i < processorQueueThreads; i++) {
            processorQueue.put(new File(Constants.POISON_PILL));
        }

        processorPool.shutdown();
        processorPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // Put one poison pill per analyzer thread
        for (int i = 0; i < analyzerQueueThreads; i++) {
            analyzerQueue.put(new File(Constants.POISON_PILL));
        }

        analyzerPool.shutdown();
        analyzerPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }







    private static Properties loadProperties(String filePath) throws IOException {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(filePath)) {
            props.load(reader);
            logger.info("Properties file loaded from: {}", filePath);
        } catch (IOException e) {
            logger.error("Error reading properties file from {}: {}", filePath, e.getMessage());
            throw e;
        }
        return props;
    }

    private static List<File> getSpecificConfigFiles() {
        List<File> configFiles = new ArrayList<>();
        File binDir = new File(Constants.COMMON_CONFIG_FILE_PATH).getParentFile();
        if (binDir.exists() && binDir.isDirectory()) {
            File[] files = binDir.listFiles((dir, name) -> name.endsWith(".properties") && !name.equals("common.properties"));
            if (files != null) {
                Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                configFiles.addAll(Arrays.asList(files));
            }
        }
        logger.info("Found {} specific config files", configFiles.size());
        return configFiles;
    }

    private static List<File> filterEnabledConfigs(List<File> allConfigs, Properties commonProps) {
        List<File> enabledConfigs = new ArrayList<>();
        for (File config : allConfigs) {
            String configName = config.getName().replace(".properties", "");
            String enabledKey = configName + ".enabled";
            String enabledValue = commonProps.getProperty(enabledKey, Constants.NO); // Default to disabled
            if (Constants.YES.equalsIgnoreCase(enabledValue)) {
                enabledConfigs.add(config);
                logger.info("Config {} is enabled", configName);
            } else {
                logger.info("Config {} is disabled", configName);
            }
        }
        logger.info("Enabled configs: {} out of {}", enabledConfigs.size(), allConfigs.size());
        return enabledConfigs;
    }

    private static Properties mergeProperties(Properties base, Properties override) {
        Properties merged = new Properties();
        merged.putAll(base);
        merged.putAll(override);
        return merged;
    }

    private static boolean isAtLeastOneVariantEnabled(Properties props) {
        return Constants.YES.equalsIgnoreCase(props.getProperty(Constants.CED0)) ||
               Constants.YES.equalsIgnoreCase(props.getProperty(Constants.CED1)) ||
               Constants.YES.equalsIgnoreCase(props.getProperty(Constants.CED2)) ||
               Constants.YES.equalsIgnoreCase(props.getProperty(Constants.CED3)) ||
               Constants.YES.equalsIgnoreCase(props.getProperty(Constants.SYNONYM)) ||
               Constants.YES.equalsIgnoreCase(props.getProperty(Constants.STOPWORD));
    }

    private static int processSingleConfig(Properties mergedProps, String configName, String matchingEngine,
            boolean isToggle, boolean isFinalRun, String startDate, String startTimeStr, boolean skipGeneration) throws Exception {
        logger.info("=============================================================");
        logger.info("Processing config: {}", configName);
        logger.info("=============================================================");

        // Update config name in properties for file naming
        mergedProps.setProperty("configName", configName);

        int generatedCount = 0;
        if (!skipGeneration) {
            // Generate raw message
            generatedCount = RawMessageGenerator.generateRawMessage(null, mergedProps);
            if (generatedCount == 0) {
                logger.info("No raw messages generated for config {}. Skipping processing.", configName);
                return generatedCount;
            }
            logger.info("Raw Message Generator completed for config: {}", configName);

            if (generatedCount > 0) {
            int fileCount = 1;
            try {
                String countFileName = Constants.OUTPUT_FILE_COUNT_PATH.replace(".txt", "_" + configName + ".txt");
                File countFile = new File(Constants.OUTPUT_FOLDER, countFileName);
                if (countFile.exists()) {
                    String countStr = new String(Files.readAllBytes(countFile.toPath())).trim();
                    fileCount = Integer.parseInt(countStr);
                }
            } catch (Exception e) {
                logger.error("Error reading file count for config {}: {}", configName, e.getMessage());
            }
                logger.info("Generated {} raw messages across {} Excel files for config {}.", generatedCount, fileCount, configName);

                // Auto-proceed for multi-config (no user prompt)
                logger.info("Auto-proceeding with processor for config: {}", configName);
            }
        } else {
            logger.info("Skipping raw message generation for config {} (after toggle).", configName);
            // For skip generation, we assume files exist from previous run
            generatedCount = 1; // Set to non-zero to proceed
        }

        List<File> excelFiles = getExcelFilesForConfig(mergedProps, configName);

        String renamePrefix = mergedProps.getProperty(Constants.WEBSERVICE) + "_" + configName + "_";

        runProcessing(matchingEngine, excelFiles, mergedProps, isToggle, isFinalRun, renamePrefix, startDate, startTimeStr, configName);
        logger.info("Processor and Analyzer completed for config: {} with matching engine: {}", configName, matchingEngine);

        return generatedCount;
    }


    private static List<File> getExcelFilesForConfig(Properties props, String configName) throws IOException {
        List<File> excelFiles = new ArrayList<>();
        // Look for files with config-specific naming first, then fallback to general pattern
        File[] files = Constants.OUTPUT_FOLDER.listFiles((dir, name) ->
            name.matches(Constants.OUTPUT_FILE_NAME + "_" + configName + "_\\d+\\.xlsx") ||
            name.matches(Constants.OUTPUT_FILE_NAME + "_\\d+\\.xlsx"));
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> {
                try {
                    String name1 = f1.getName();
                    String name2 = f2.getName();
                    // Extract the number part after the last underscore
                    int index1 = Integer.parseInt(name1.substring(name1.lastIndexOf("_") + 1).replace(Constants.XLSX_EXT, ""));
                    int index2 = Integer.parseInt(name2.substring(name2.lastIndexOf("_") + 1).replace(Constants.XLSX_EXT, ""));
                    return Integer.compare(index1, index2);
                } catch (NumberFormatException e) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
            int fileLimit = 0;
            String countFileName = Constants.OUTPUT_FILE_COUNT_PATH.replace(".txt", "_" + configName + ".txt");
            File countFile = new File(Constants.OUTPUT_FOLDER, countFileName);
            if (countFile.exists()) {
                try {
                    String countStr = new String(Files.readAllBytes(countFile.toPath())).trim();
                    fileLimit = Integer.parseInt(countStr);
                } catch (Exception e) {
                    logger.error("Error reading file count: {}", e.getMessage());
                }
            }
            if (fileLimit > 0) {
                for (int i = 0; i < Math.min(files.length, fileLimit); i++) {
                    excelFiles.add(files[i]);
                }
            }
        }
        return excelFiles;
    }
}
