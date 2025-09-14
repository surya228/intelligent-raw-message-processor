package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnalyzerRunnable implements Runnable {
    private final BlockingQueue<File> inputQueue;
    private final String matchingEngine;
    private final boolean isToggle;
    private final boolean isFinalRun;
    private final String renamePrefix;
    private final String startDate;
    private final String startTimeStr;
    private final Properties props;

    private static final Logger logger = LoggerFactory.getLogger(AnalyzerRunnable.class);
    

    public AnalyzerRunnable(BlockingQueue<File> inputQueue, String matchingEngine, boolean isToggle, boolean isFinalRun, String renamePrefix, String startDate, String startTimeStr, Properties props) {
        this.inputQueue = inputQueue;
        this.matchingEngine = matchingEngine;
        this.isToggle = isToggle;
        this.isFinalRun = isFinalRun;
        this.renamePrefix = renamePrefix;
        this.startDate = startDate;
        this.startTimeStr = startTimeStr;
        this.props = props;
    }

    @Override
    public void run() {
        logger.info("Entering run()");
        try {
            while (true) {
                File file = inputQueue.take();
                if (Constants.POISON_PILL.equals(file.getName())) {
                    break;
                }
                MessageResponseAnalyzer.analyseResponseAndPrepareResults(matchingEngine, file, props);

                // Rename if this is the final run or no toggle
                if (!isToggle || (isToggle && isFinalRun)) {
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
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            logger.info("Exiting run()");
        }
    }
}
