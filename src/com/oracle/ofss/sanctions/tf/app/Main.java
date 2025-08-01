package com.oracle.ofss.sanctions.tf.app;

public class Main {
    public static void main(String[] args) throws Exception {
        RawMessageGenerator.generateRawMessage();
        MessageProcessingUtility.screenRawMsg();
    }
}
