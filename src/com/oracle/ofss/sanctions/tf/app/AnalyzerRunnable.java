package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.util.concurrent.BlockingQueue;

public class AnalyzerRunnable implements Runnable {
    private final BlockingQueue<File> inputQueue;
    private final String matchingEngine;

    public AnalyzerRunnable(BlockingQueue<File> inputQueue, String matchingEngine) {
        this.inputQueue = inputQueue;
        this.matchingEngine = matchingEngine;
    }

    @Override
    public void run() {
        try {
            while (true) {
                File file = inputQueue.take();
                if (Constants.POISON_PILL.equals(file.getName())) {
                    break;
                }
                MessageResponseAnalyzer.analyseResponseAndPrepareResults(matchingEngine, file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
