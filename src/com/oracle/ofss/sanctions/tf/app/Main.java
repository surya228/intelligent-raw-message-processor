package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

public class Main {
    public static void main(String[] args) throws Exception {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        long startTime = System.currentTimeMillis();
        if(props.getProperty(Constants.MODULE_RAW_MSG_GENERATOR).equalsIgnoreCase(Constants.YES))
            RawMessageGenerator.generateRawMessage();

        ToggleMatchingEngine toggleMatchingEngine = new ToggleMatchingEngine();

        if(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR).equalsIgnoreCase(Constants.YES)) {
            String currentMatchingEngine = toggleMatchingEngine.findCurrentMatchingEngine();
            System.out.println("Current Matching Engine::: "+ currentMatchingEngine);
            MessageProcessingUtility.screenRawMsg(currentMatchingEngine);
            MessageResponseAnalyzer.analyseResponseAndPrepareResults(currentMatchingEngine);
        }


        if(props.getProperty(Constants.TOGGLE_MATCHING_ENGINE).equalsIgnoreCase(Constants.YES)){
            String newEsOs = toggleMatchingEngine.toggleMatchingEngine();
            System.out.println("Matching engine set to ::: "+ newEsOs);

            if(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR).equalsIgnoreCase(Constants.YES)){
                MessageProcessingUtility.screenRawMsg(newEsOs);
                MessageResponseAnalyzer.analyseResponseAndPrepareResults(newEsOs);
            }
        }

        // Renaming output files
        SimpleDateFormat dateFormat = new SimpleDateFormat(Constants.DATE_SUFFIX_FORMAT);
        SimpleDateFormat timeFormat = new SimpleDateFormat(Constants.TIME_SUFFIX_FORMAT);
        Date now = new Date();
        String date = dateFormat.format(now);
        String time = timeFormat.format(now);
        String webservice = props.getProperty(Constants.WEBSERVICE);
        String baseName = (webservice != null ? webservice : "")+"_"+date+"_"+time;

        File[] filesToRename = {
                Constants.OUTPUT_XLSX_FILE_PATH,
                Constants.OUTPUT_JSON_FILE_PATH
        };
        String[] extensions = {Constants.XLSX_EXT, Constants.JSON_EXT};

        for (int i = 0; i < filesToRename.length; i++) {
            File original = filesToRename[i];
            if (!original.exists()) continue;

            String fileName = baseName + extensions[i];
            File newFile = new File(Constants.OUTPUT_FOLDER, fileName);
            if (original.renameTo(newFile)) {
                System.out.println("Renamed " + original.getName() + " to " + newFile.getName());
            } else {
                System.err.println("Failed to rename " + original.getName());
            }
        }

        System.out.println("Output files renamed");

        saveConfigProperties(props);

        System.out.println("Saved config file");

        long endTime = System.currentTimeMillis();
        System.out.println("\n==========================================================");
        System.out.println("Total time taken by utility: "+ (endTime - startTime) / 1000L + " seconds");
        System.out.println("=========================================================");
    }

    private static void saveConfigProperties(Properties props) {
        String webservice = props.getProperty(Constants.WEBSERVICE);
        String fileName = (webservice != null ? webservice : Constants.DEFAULT_CONFIG_BASE) + ".properties";
        File configFile = new File(Constants.OUTPUT_FOLDER, fileName);
        File originalConfig = new File(Constants.CONFIG_FILE_PATH);
        try {
            Files.copy(originalConfig.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Config properties saved to " + configFile.getName());
        } catch (IOException e) {
            System.err.println("Error saving config properties: " + e.getMessage());
        }
    }
}
