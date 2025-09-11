package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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
        if(props.getProperty(Constants.MODULE_RAW_MSG_GENERATOR).equalsIgnoreCase("Y"))
            RawMessageGenerator.generateRawMessage();

        ToggleMatchingEngine toggleMatchingEngine = new ToggleMatchingEngine();

        if(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR).equalsIgnoreCase("Y")) {
            String currentMatchingEngine = toggleMatchingEngine.findCurrentMatchingEngine();
            System.out.println("Current Matching Engine::: "+ currentMatchingEngine);
            MessageProcessingUtility.screenRawMsg(currentMatchingEngine);
            MessageResponseAnalyzer.analyseResponseAndPrepareResults(currentMatchingEngine);
        }


        if(props.getProperty(Constants.TOGGLE_MATCHING_ENGINE).equalsIgnoreCase("Y")){
            String newEsOs = toggleMatchingEngine.toggleMatchingEngine();
            System.out.println("Matching engine set to ::: "+ newEsOs);

            if(props.getProperty(Constants.MODULE_RAW_MSG_PROCESSOR).equalsIgnoreCase("Y")){
                MessageProcessingUtility.screenRawMsg(newEsOs);
                MessageResponseAnalyzer.analyseResponseAndPrepareResults(newEsOs);
            }
        }

        // Renaming output files
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMyy");
        String date = sdf.format(new Date());
        String webservice = props.getProperty(Constants.WEBSERVICE);
        String baseName = (webservice != null ? webservice : "")+"_"+date;

        File[] filesToRename = {
                Constants.OUTPUT_XLSX_FILE_PATH,
                new File(Constants.OUTPUT_FOLDER, Constants.OUTPUT_FILE_NAME + ".json")
        };
        String[] extensions = {".xlsx", ".json"};

        for (int i = 0; i < filesToRename.length; i++) {
            File original = filesToRename[i];
            if (!original.exists()) continue;

            int counter = 1;
            File newFile = new File(Constants.OUTPUT_FOLDER, baseName + "_" + counter + extensions[i]);
            while (newFile.exists()) {
                counter++;
                newFile = new File(Constants.OUTPUT_FOLDER, baseName + "_" + counter + extensions[i]);
            }

            if (original.renameTo(newFile)) {
                System.out.println("Renamed " + original.getName() + " to " + newFile.getName());
            } else {
                System.err.println("Failed to rename " + original.getName());
            }
        }

        long endTime = System.currentTimeMillis();
        System.out.println("\n==========================================================");
        System.out.println("Total time taken by utility: "+ (endTime - startTime) / 1000L + " seconds");
        System.out.println("=========================================================");

    }
}
