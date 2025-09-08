package com.oracle.ofss.sanctions.tf.app;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class MessageResponseAnalyzer {
    public static void analyseResponseAndPrepareResults() throws Exception {
        try {
            long startTime = System.currentTimeMillis();

            System.out.println("\n=============================================================");
            System.out.println("                  RESPONSE ANALYZER STARTED                  ");
            System.out.println("=============================================================");

            Properties props = new Properties();
            try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }
            String tagName = props.getProperty(Constants.TAGNAME);
            String msgCategory = "";
            String transactionService = props.getProperty(Constants.TRANSACTION_SERVICE);
            String watchListType = props.getProperty(Constants.WATCHLIST_TYPE);
            String webServiceId = props.getProperty(Constants.WEBSERVICE_ID);
            if(transactionService.equalsIgnoreCase("SWIFT")) msgCategory="SWIFT";
            else if(transactionService.equalsIgnoreCase("FEDWIRE")) msgCategory="FEDWIRE";
            else if(transactionService.equalsIgnoreCase("ISO20022")) msgCategory="SEPA";
            System.out.println("tagName: " + tagName);
            processAllResponses(tagName, msgCategory, watchListType, webServiceId);
            System.out.println("\n=============================================================");
            System.out.println("                   RESPONSE ANALYZER ENDED                   ");
            System.out.println("=============================================================");
            long endTime = System.currentTimeMillis();

            System.out.println("Time taken by Message Response Analyzer: " + (endTime - startTime) / 1000L + " seconds");

        } catch (Exception e){
            e.printStackTrace();
            throw new Exception("Something went wrong while analyzing responses",e);
        }
    }

    public static void processAllResponses(String tagName, String msgCategory, String watchListType, String webServiceId) throws Exception {
        try (FileInputStream fis = new FileInputStream(Constants.OUTPUT_XLSX_FILE_PATH);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);

            // Collect all transactionTokens and row data in memory
            List<Long> transactionTokens = new ArrayList<>();
            Map<Long, Integer> tokenToRowNum = new HashMap<>();
            Map<Long, String> tokenToTargetColumn = new HashMap<>();
            Map<Long, String> tokenToUid = new HashMap<>();

            int rowNum = 1; // Skip header
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // Skip header

                Cell tokenCell = row.getCell(Constants.PROCESSOR_COLUMN_NUMBER);
                if (tokenCell == null) continue;

                long transactionToken = 0;
                if (tokenCell.getCellType() == CellType.NUMERIC) {
                    transactionToken = (long) tokenCell.getNumericCellValue();
                } else if (tokenCell.getCellType() == CellType.STRING) {
                    String value = tokenCell.getStringCellValue().trim();
                    if (!value.isEmpty()) {
                        transactionToken = Long.parseLong(value);
                    }
                }
                if (transactionToken == 0) continue;

                transactionTokens.add(transactionToken);
                tokenToRowNum.put(transactionToken, row.getRowNum());

                Cell targetColumnCell = row.getCell(6);
                Cell uidCell = row.getCell(8);
                tokenToTargetColumn.put(transactionToken, targetColumnCell != null ? targetColumnCell.getStringCellValue() : "");
                tokenToUid.put(transactionToken, uidCell != null ? uidCell.getStringCellValue() : "");
            }

            // Bulk fetch feedback responses
            Map<Long, JSONObject> feedbackMap = getBulkResponsesFromFeedbackTable(transactionTokens, msgCategory);

            // Bulk fetch WLS column names
            int msgCategoryNumber = msgCategory.equalsIgnoreCase("SWIFT") ? 1 : msgCategory.equalsIgnoreCase("FEDWIRE") ? 2 : 3;
            Map<Long, Map<Long, String>> tokenToCsvColumnNamesMap = getBulkColumnNameCsvWLS(transactionTokens, msgCategoryNumber);

            // Prepare styles
            Font boldFont = workbook.createFont();
            boldFont.setBold(true);

            CellStyle highlightGreen = workbook.createCellStyle();
            highlightGreen.setFillForegroundColor(IndexedColors.BRIGHT_GREEN.getIndex());
            highlightGreen.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            highlightGreen.setFont(boldFont);

            CellStyle highlightRed = workbook.createCellStyle();
            highlightRed.setFillForegroundColor(IndexedColors.RED.getIndex());
            highlightRed.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            highlightRed.setFont(boldFont);

//            CellStyle highlightYellow = workbook.createCellStyle();
//            highlightYellow.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
//            highlightYellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);


            // Parallel processing of rows
            ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (long transactionToken : transactionTokens) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        JSONObject eachResponse = feedbackMap.get(transactionToken);
                        if (eachResponse == null || !eachResponse.has("matches")) return;

                        JSONArray matches = eachResponse.getJSONArray("matches");
                        int truePositives = 0;
                        String targetColumnName = tokenToTargetColumn.get(transactionToken);
                        String uid = tokenToUid.get(transactionToken);
                        Map<Long, String> csvColumnNamesMap = tokenToCsvColumnNamesMap.getOrDefault(transactionToken, Collections.emptyMap());

                        boolean failedDueToColumnMismatch = false;
                        for (int i = 0; i < matches.length(); i++) {
                            JSONObject match = matches.getJSONObject(i);
                            String tagNameCsv = match.optString("tagName", "");
                            Set<String> tagNames = Arrays.stream(tagNameCsv.split(",")).map(String::trim).collect(Collectors.toSet());

                            String targetUid = match.getString("matchedWatchlistId");
                            Long responseId = match.getLong("responseID");
                            String columnNamesCsvWLS = csvColumnNamesMap.get(responseId);
                            Set<String> columnNames = columnNamesCsvWLS != null ? Arrays.stream(columnNamesCsvWLS.split(",")).collect(Collectors.toSet()) : Collections.emptySet();

                            boolean flag = uid.equals(targetUid)
                                    && watchListType.equalsIgnoreCase(match.optString("watchlistType"))
                                    && webServiceId.equalsIgnoreCase(String.valueOf(match.getInt("webServiceID")))
                                    && tagNames.contains(tagName);

                            if (flag) {
                                if (columnNames.stream().anyMatch(col -> col.equalsIgnoreCase(targetColumnName))) { // Case-insensitive match
                                    truePositives++;failedDueToColumnMismatch = false;
                                    break; // Early exit if we only need count >=1
                                } else {
                                    failedDueToColumnMismatch = true;
                                }
                            }
                        }

                        String testStatus = truePositives > 0 ? Constants.PASS : Constants.FAIL;

                        // Update sheet in synchronized block for thread safety
                        synchronized (sheet) {
                            Row row = sheet.getRow(tokenToRowNum.get(transactionToken));
                            Cell testStatusCell = row.getCell(Constants.ANALYZER_COLUMN_NUMBER);
                            if (testStatusCell == null) testStatusCell = row.createCell(Constants.ANALYZER_COLUMN_NUMBER);
                            testStatusCell.setCellValue(testStatus);
                            testStatusCell.setCellStyle(testStatus.equalsIgnoreCase(Constants.PASS) ? highlightGreen : highlightRed);

                            Cell commentsCell = row.getCell(Constants.ANALYZER_COLUMN_NUMBER+1);
                            if (commentsCell == null) commentsCell = row.createCell(Constants.ANALYZER_COLUMN_NUMBER+1);

                            if(failedDueToColumnMismatch){
                                commentsCell.setCellValue(Constants.COLUMN_MISMATCH_COMMENT);
                            } else if (testStatus.equalsIgnoreCase(Constants.FAIL)) {
                                commentsCell.setCellValue(Constants.NO_MATCH_COMMENT);
                            }

                        }

//                        System.out.println("------------------------------------------------------------");
//                        System.out.println("transactionToken::: "+ transactionToken);
//                        System.out.println("testStatus::: "+ testStatus);
//                        System.out.println("------------------------------------------------------------");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for all tasks to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            executor.shutdown();
            sheet.autoSizeColumn(Constants.ANALYZER_COLUMN_NUMBER + 1);

            // Write the updated workbook once
            try (FileOutputStream fos = new FileOutputStream(Constants.OUTPUT_XLSX_FILE_PATH)) {
                workbook.write(fos);
            }

        }
    }

    private static Map<Long, JSONObject> getBulkResponsesFromFeedbackTable(List<Long> transactionTokens, String msgCategory) throws Exception {
        Map<Long, JSONObject> feedbackMap = new HashMap<>();
        if (transactionTokens.isEmpty()) return feedbackMap;

        Connection connection = SQLUtility.getDbConnection();
        try {
            // Batch in chunks to avoid IN clause limits
            int batchSize = 1000;
            for (int i = 0; i < transactionTokens.size(); i += batchSize) {
                List<Long> batch = transactionTokens.subList(i, Math.min(i + batchSize, transactionTokens.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                String query = "SELECT N_TRAX_TOKEN, C_FEEDBACK_MESSAGE FROM fcc_tf_feedback WHERE N_TRAX_TOKEN IN (" + placeholders + ") AND V_MSG_CATEGORY = ?";
                PreparedStatement pst = connection.prepareStatement(query);
                for (int j = 0; j < batch.size(); j++) {
                    pst.setLong(j + 1, batch.get(j));
                }
                pst.setString(batch.size() + 1, msgCategory);
                try (ResultSet rs = pst.executeQuery()) {
                    while (rs.next()) {
                        String jsonString = rs.getString("C_FEEDBACK_MESSAGE");
                        if (jsonString != null && !jsonString.isEmpty()) {
                            feedbackMap.put(rs.getLong("N_TRAX_TOKEN"), new JSONObject(jsonString));
                        }
                    }
                }
            }
        } finally {
            if (connection != null) connection.close();
        }
        return feedbackMap;
    }

    private static Map<Long, Map<Long, String>> getBulkColumnNameCsvWLS(List<Long> transactionTokens, int msgCategory) throws Exception {
        Map<Long, Map<Long, String>> tokenToColumnMap = new HashMap<>();
        if (transactionTokens.isEmpty()) return tokenToColumnMap;

        Connection connection = SQLUtility.getDbConnection();
        try {
            int batchSize = 1000;
            for (int i = 0; i < transactionTokens.size(); i += batchSize) {
                List<Long> batch = transactionTokens.subList(i, Math.min(i + batchSize, transactionTokens.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                String query = "SELECT N_GRP_MSG_ID, N_RESPONSE_ID, V_COLUMN_NAME FROM fcc_tf_rt_wls_response WHERE n_grp_msg_id IN (" + placeholders + ") AND n_msg_category = ?";
                PreparedStatement pst = connection.prepareStatement(query);
                for (int j = 0; j < batch.size(); j++) {
                    pst.setLong(j + 1, batch.get(j));
                }
                pst.setInt(batch.size() + 1, msgCategory);
                try (ResultSet rs = pst.executeQuery()) {
                    while (rs.next()) {
                        long token = rs.getLong("N_GRP_MSG_ID");
                        long responseId = rs.getLong("N_RESPONSE_ID");
                        String columnName = rs.getString("V_COLUMN_NAME");
                        tokenToColumnMap.computeIfAbsent(token, k -> new HashMap<>()).put(responseId, columnName);
                    }
                }
            }
        } finally {
            if (connection != null) connection.close();
        }
        return tokenToColumnMap;
    }
}
