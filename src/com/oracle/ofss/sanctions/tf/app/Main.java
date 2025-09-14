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
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            logger.error("Error reading properties file: " + e.getMessage());
            throw e;
        }
        saveConfigProperties(props);
        logger.info("Saved config file");

        Date startDateObj = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("ddMMyy");
        SimpleDateFormat timeFormat = new SimpleDateFormat("HHmmss");
        String startDate = dateFormat.format(startDateObj);
        String startTimeStr = timeFormat.format(startDateObj);
        String renamePrefix = props.getProperty(Constants.WEBSERVICE) + "_";

        long startTime = System.currentTimeMillis();

        boolean generate = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.MODULE_RAW_MSG_GENERATOR));
        boolean process = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR));
        boolean isToggle = Constants.YES.equalsIgnoreCase(props.getProperty(Constants.TOGGLE_MATCHING_ENGINE));

        ToggleMatchingEngine toggleMatchingEngine = new ToggleMatchingEngine();
        String matchingEngine = toggleMatchingEngine.findCurrentMatchingEngine();
        logger.info("Current Matching Engine::: " + matchingEngine);

        // Delete previous output files and count file
        File countFile = new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_COUNT_PATH);
        if (countFile.exists()) {
            if (countFile.delete()) {
                logger.info("Deleted previous count file: " + countFile.getName());
            } else {
                logger.error("Failed to delete previous count file: " + countFile.getName());
            }
        }
        File[] prevFiles = Constants.OUTPUT_FOLDER.listFiles((dir, name) -> name.matches(Constants.OUTPUT_FILE_NAME+"_\\d+\\.xlsx"));
        if (prevFiles != null) {
            for (File file : prevFiles) {
                if (file.delete()) {
                    logger.info("Deleted previous output file: " + file.getName());
                } else {
                    logger.error("Failed to delete previous output file: " + file.getName());
                }
            }
        }
        if (generate) {
            int generatedCount = RawMessageGenerator.generateRawMessage(null); // Generation is always sequential
            if (generatedCount == 0) {
                logger.info("No raw messages generated. Exiting utility.");
                System.exit(0);
            }
        }

        if (process) {
            List<File> excelFiles = getExcelFiles(props);

            runProcessing(matchingEngine, excelFiles, props, isToggle, false, renamePrefix, startDate, startTimeStr);

            if (isToggle) {
                matchingEngine = toggleMatchingEngine.toggleMatchingEngine();
                logger.info("Matching engine toggled to ::: " + matchingEngine);
                runProcessing(matchingEngine, excelFiles, props, isToggle, true, renamePrefix, startDate, startTimeStr);
            }
        }

        long endTime = System.currentTimeMillis();
        logger.info("\n==========================================================");
        logger.info("Total time taken by utility: "+ (endTime - startTime) / 1000L + " seconds");
        logger.info("=========================================================");
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
            processorPool.submit(new ProcessorRunnable(processorQueue, analyzerQueue, matchingEngine));
        }
        for (int i = 0; i < analyzerThreads; i++) {
            analyzerPool.submit(new AnalyzerRunnable(analyzerQueue, matchingEngine, isToggle, isFinalRun, renamePrefix, startDate, startTimeStr));
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

    private static void renameFile(File file, String matchingEngine, boolean isToggle, String renamePrefix, String startDate, String startTimeStr) {
        String sequence = file.getName().replace(Constants.OUTPUT_FILE_NAME+"_", "").replace(".xlsx", "");
        String enginePart = isToggle ? "OS_OT" : matchingEngine;
        String newName = renamePrefix + enginePart + "_" + startDate + "_" + startTimeStr + "_" + sequence + ".xlsx";
        File newFile = new File(Constants.OUTPUT_FOLDER, newName);
        if (file.renameTo(newFile)) {
            logger.info("Renamed " + file.getName() + " to " + newName);
        } else {
            logger.error("Failed to rename " + file.getName());
        }
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
                    logger.error("Error reading file count: " + e.getMessage());
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
            logger.info("Config properties saved to " + configFile.getName());
        } catch (IOException e) {
            logger.error("Error saving config properties: " + e.getMessage());
        }
    }
}
