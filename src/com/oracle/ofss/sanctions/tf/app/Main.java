package com.oracle.ofss.sanctions.tf.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
import java.util.Scanner;

public class Main {
    static {
        try {
            File logDir = new File(Constants.OUTPUT_FOLDER, "log");
            if (!logDir.exists()) {
                if (!logDir.mkdirs()) {
                    System.err.println("Could not create log directory at " + logDir.getAbsolutePath());
                }
            }
            // Truncate existing log files on startup to ensure a fresh run
            try {
                String[] logFiles = new String[] {
                        "UtilityMain.log",
                        "RawMessageGenrator.log",
                        "MessageProcessor.log",
                        "MessageAnalyzer.log"
                };
                for (String lf : logFiles) {
                    File f = new File(logDir, lf);
                    if (f.exists()) {
                        try {
                            new java.io.FileOutputStream(f, false).close();
                        } catch (Throwable clearEx) {
                            // Fallback to delete if truncation fails
                            if (!f.delete()) {
                                System.err.println("Failed to clear log file: " + f.getAbsolutePath() + " due to: " + clearEx.getMessage());
                            }
                        }
                    }
                }
            } catch (Throwable t2) {
                System.err.println("Failed while clearing previous log files: " + t2.getMessage());
            }
            System.setProperty("log.dir", logDir.getAbsolutePath());
        } catch (Throwable t) {
            System.err.println("Failed to initialize log directory: " + t.getMessage());
        }
    }
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        logger.info("=============================================================");
        logger.info("      INTELLIGENT RAW MESSAGE PROCESSOR UTILITY STARTED      ");
        logger.info("=============================================================");
        Date startDateObj = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat(Constants.DATE_SUFFIX_FORMAT);
        SimpleDateFormat timeFormat = new SimpleDateFormat(Constants.TIME_SUFFIX_FORMAT);
        String startDate = dateFormat.format(startDateObj);
        String startTimeStr = timeFormat.format(startDateObj);

        long startTime = System.currentTimeMillis();

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

        for (File specificConfig : enabledConfigs) {
            try {
                String configName = specificConfig.getName().replace(".properties", "");
                Properties specificProps = loadProperties(specificConfig.getPath());
                Properties mergedProps = mergeProperties(commonProps, specificProps);

                processSingleConfig(mergedProps, configName, matchingEngine, isToggle, false, startDate, startTimeStr);
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
                    Properties mergedProps = mergeProperties(commonProps, specificProps);

                    processSingleConfig(mergedProps, configName, matchingEngine, isToggle, true, startDate, startTimeStr);
                } catch (Exception e) {
                    logger.error("Error re-processing config {}: {}", specificConfig.getName(), e.getMessage());
                    // Continue with other configs
                }
            }

            logger.info("All configs re-processed with matching engine: {}", matchingEngine);
        }

        logger.info("=============================================================");
        logger.info("     INTELLIGENT RAW MESSAGE PROCESSOR UTILITY COMPLETED     ");
        logger.info("=============================================================");
        long endTime = System.currentTimeMillis();
        logger.info("Total time taken by utility: {} seconds", (endTime - startTime) / 1000L);
    }

    private static void deletePreviousFiles() {
        File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
        if (countFile.exists()) {
            if (countFile.delete()) {
                logger.info("Deleted previous count file: {}", countFile.getName());
            } else {
                logger.error("Failed to delete previous count file: {}", countFile.getName());
            }
        }
        File[] prevFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) -> name.matches(Constants.OUTPUT_FILE_NAME+"_\\d+\\.xlsx"));
        if (prevFiles != null) {
            for (File file : prevFiles) {
                if (file.delete()) {
                    logger.info("Deleted previous output file: {}", file.getName());
                } else {
                    logger.error("Failed to delete previous output file: {}", file.getName());
                }
            }
        }
    }

    /**
     * Renames output files by appending a timestamp and webservice name.
     * @param props Properties containing configuration details like webservice name.
     */
    private static void runProcessing(String matchingEngine, List<File> excelFiles, Properties props, boolean isToggle, boolean isFinalRun, String renamePrefix, String startDate, String startTimeStr) throws Exception {
        int processorThreads = Integer.parseInt(props.getProperty(Constants.PROCESSOR_THREADS, String.valueOf(Constants.DEFAULT_THREAD_COUNT)));
        int analyzerThreads = Integer.parseInt(props.getProperty(Constants.ANALYZER_THREADS, String.valueOf(Constants.DEFAULT_THREAD_COUNT)));

        BlockingQueue<File> processorQueue = new LinkedBlockingQueue<>();
        BlockingQueue<File> analyzerQueue = new LinkedBlockingQueue<>();

        ExecutorService processorPool = Executors.newFixedThreadPool(processorThreads);
        ExecutorService analyzerPool = Executors.newFixedThreadPool(analyzerThreads);

        for (int i = 0; i < processorThreads; i++) {
            processorPool.submit(new ProcessorRunnable(processorQueue, analyzerQueue, matchingEngine, props));
        }
        for (int i = 0; i < analyzerThreads; i++) {
            analyzerPool.submit(new AnalyzerRunnable(analyzerQueue, matchingEngine, isToggle, isFinalRun, renamePrefix, startDate, startTimeStr, props));
        }

        // Add existing files to queue for processing
        for (File file : excelFiles) {
            processorQueue.put(file);
        }

        // Put one poison pill per processor thread
        for (int i = 0; i < processorThreads; i++) {
            processorQueue.put(new File(Constants.POISON_PILL));
        }

        processorPool.shutdown();
        processorPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);

        // Put one poison pill per analyzer thread
        for (int i = 0; i < analyzerThreads; i++) {
            analyzerQueue.put(new File(Constants.POISON_PILL));
        }

        analyzerPool.shutdown();
        analyzerPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    private static List<File> getExcelFiles(Properties props) throws IOException {
        List<File> excelFiles = new ArrayList<>();
        File[] files = Constants.OUTPUT_FOLDER.listFiles((dir, name) -> name.matches(Constants.OUTPUT_FILE_NAME+"_\\d+\\.xlsx"));
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> {
                try {
                    int index1 = Integer.parseInt(f1.getName().replaceFirst(Constants.OUTPUT_FILE_NAME + "_", "").replace(Constants.XLSX_EXT, ""));
                    int index2 = Integer.parseInt(f2.getName().replaceFirst(Constants.OUTPUT_FILE_NAME + "_", "").replace(Constants.XLSX_EXT, ""));
                    return Integer.compare(index1, index2);
                } catch (NumberFormatException e) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
            int fileLimit = 0;
            File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
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

    /**
     * Saves the configuration properties to a file with a name based on the webservice.
     * @param props Properties to save.
     */
    private static void saveConfigProperties(Properties props) {
        String webservice = props.getProperty(Constants.WEBSERVICE);
        String fileName = (webservice != null ? webservice : Constants.DEFAULT_CONFIG_BASE) + ".properties";
        File configFile = new File(Constants.OUTPUT_FOLDER, fileName);
        File originalConfig = new File(Constants.CONFIG_FILE_PATH);
        try {
            Files.copy(originalConfig.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Config properties saved to {}", configFile.getName());
        } catch (IOException e) {
            logger.error("Error saving config properties: {}", e.getMessage());
        }
    }

    private static Properties loadProperties() throws IOException {
        return loadProperties(Constants.CONFIG_FILE_PATH);
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

    private static void processSingleConfig(Properties mergedProps, String configName, String matchingEngine,
            boolean isToggle, boolean isFinalRun, String startDate, String startTimeStr) throws Exception {
        logger.info("=============================================================");
        logger.info("Processing config: {}", configName);
        logger.info("=============================================================");

        // Update config name in properties for file naming
        mergedProps.setProperty("configName", configName);

        // Delete previous output files for this config
        deletePreviousFilesForConfig(configName);

        // Generate raw message
        int generatedCount = RawMessageGenerator.generateRawMessage(null, mergedProps);
        if (generatedCount == 0) {
            logger.info("No raw messages generated for config {}. Skipping processing.", configName);
            return;
        }
        logger.info("Raw Message Generator completed for config: {}", configName);

        if (generatedCount > 0) {
            int fileCount = 1;
            try {
                File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
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

        List<File> excelFiles = getExcelFilesForConfig(mergedProps, configName);

        String renamePrefix = mergedProps.getProperty(Constants.WEBSERVICE) + "_" + configName + "_";

        runProcessing(matchingEngine, excelFiles, mergedProps, isToggle, isFinalRun, renamePrefix, startDate, startTimeStr);
        logger.info("Processor and Analyzer completed for config: {} with matching engine: {}", configName, matchingEngine);
    }

    private static void deletePreviousFilesForConfig(String configName) {
        // Delete files with config-specific naming pattern
        File[] prevFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) ->
            name.matches(Constants.OUTPUT_FILE_NAME + "_" + configName + "_\\d+\\.xlsx") ||
            name.matches(Constants.OUTPUT_FILE_NAME + "_\\d+\\.xlsx")); // fallback for old pattern
        if (prevFiles != null) {
            for (File file : prevFiles) {
                if (file.delete()) {
                    logger.info("Deleted previous output file: {}", file.getName());
                } else {
                    logger.error("Failed to delete previous output file: {}", file.getName());
                }
            }
        }
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
            File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
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
