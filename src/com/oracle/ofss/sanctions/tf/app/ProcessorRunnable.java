package com.oracle.ofss.sanctions.tf.app;

import java.io.File;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;

public class ProcessorRunnable implements Runnable {
    private final BlockingQueue<File> inputQueue;
    private final BlockingQueue<File> outputQueue;
    private final String matchingEngine;
    private final Properties props;

    public ProcessorRunnable(BlockingQueue<File> inputQueue, BlockingQueue<File> outputQueue, String matchingEngine, Properties props) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.matchingEngine = matchingEngine;
        this.props = props;
    }

    @Override
    public void run() {
        try {
            while (true) {
                File file = inputQueue.take();
                if (Constants.POISON_PILL.equals(file.getName())) {
                    break;
                }
                MessageProcessingUtility.screenRawMsg(matchingEngine, file, props);
                outputQueue.put(file);
            }
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
    }
}
