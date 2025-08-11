package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
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
        if(props.getProperty("module.rawMsgGenerator").equalsIgnoreCase("Y"))
            RawMessageGenerator.generateRawMessage();

        if(props.getProperty("module.rawMsgProcessor").equalsIgnoreCase("Y"))
            MessageProcessingUtility.screenRawMsg();

        if(props.getProperty("module.rawMsgAnalyzer").equalsIgnoreCase("Y"))
            MessageResponseAnalyzer.analyseResponseAndPrepareResults();


        long endTime = System.currentTimeMillis();
        System.out.println("\n==========================================================");
        System.out.println("Total time taken by utility: "+ (endTime - startTime) / 1000L + " seconds");
        System.out.println("=========================================================");

    }
}
