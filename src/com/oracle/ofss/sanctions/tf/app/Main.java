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

        Properties props = loadProperties();
        saveConfigProperties(props);

        String renamePrefix = props.getProperty(Constants.WEBSERVICE) + "_";

        long startTime = System.currentTimeMillis();

        boolean generate = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.MODULE_RAW_MSG_GENERATOR));
        boolean process = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR));
        boolean isToggle = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.TOGGLE_MATCHING_ENGINE));


        // Delete previous output files and count file
        deletePreviousFiles();

        if (generate) {
            int generatedCount = RawMessageGenerator.generateRawMessage(null, props); // Generation is always sequential
            if (generatedCount == 0) {
                logger.info("No raw messages generated. Exiting utility.");
                System.exit(0);
            }
            logger.info("Raw Message Generator Completed");
        }

        ToggleMatchingEngine toggleMatchingEngine = new ToggleMatchingEngine();
        String matchingEngine = toggleMatchingEngine.findCurrentMatchingEngine();
        logger.info("Proceeding for Message Processor and Analyzer");
        logger.info("Current Matching Engine::: {}", matchingEngine);

        if (process) {
            List<File> excelFiles = getExcelFiles(props);

            runProcessing(matchingEngine, excelFiles, props, isToggle, false, renamePrefix, startDate, startTimeStr);
            logger.info("Processor and Analyzer Completed with matching engine :: {}",matchingEngine);
            if (isToggle) {
                logger.info("Toggling Matching engine....");
                matchingEngine = toggleMatchingEngine.toggleMatchingEngine();
                logger.info("Matching engine toggled to ::: {}", matchingEngine);
                runProcessing(matchingEngine, excelFiles, props, isToggle, true, renamePrefix, startDate, startTimeStr);
                logger.info("Processor and Analyzer Completed after toggling matching engine to :: {}",matchingEngine);

            }
        }
        logger.info("=============================================================");
        logger.info("     INTELLIGENT RAW MESSAGE PROCESSOR UTILITY COMPLETED     ");
        logger.info("=============================================================");
        long endTime = System.currentTimeMillis();
        logger.info("Total time taken by utility: {} Seconds ", (endTime - startTime) / 1000L );
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
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
            logger.info("Properties file loaded");
        } catch (IOException e) {
            logger.error("Error reading properties file: {}", e.getMessage());
            throw e;
        }
        return props;
    }
}
