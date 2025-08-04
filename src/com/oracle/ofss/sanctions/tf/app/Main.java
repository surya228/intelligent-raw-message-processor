package com.oracle.ofss.sanctions.tf.app;

public class Main {
    public static void main(String[] args) throws Exception {
        long startTime = System.currentTimeMillis();
        RawMessageGenerator.generateRawMessage();
        MessageProcessingUtility.screenRawMsg();
        MessageResponseAnalyzer.analyseResponseAndPrepareResults();
        long endTime = System.currentTimeMillis();
        System.out.println("\n==========================================================");
        System.out.println("Total time taken by utility: "+ (endTime - startTime) / 1000L + " seconds");
        System.out.println("=========================================================");

    }
}
