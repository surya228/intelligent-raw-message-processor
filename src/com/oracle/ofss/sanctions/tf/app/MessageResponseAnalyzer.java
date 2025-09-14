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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class MessageResponseAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MessageResponseAnalyzer.class);
    public static void analyseResponseAndPrepareResults(String matchingEngine, File excelFile) throws Exception {
        analyseResponseAndPrepareResults(matchingEngine, Collections.singletonList(excelFile));
    }

    public static void analyseResponseAndPrepareResults(String matchingEngine, List<File> excelFiles) throws Exception {
        long startTime = System.currentTimeMillis();

        logger.info("\n=============================================================");
        logger.info("                  RESPONSE ANALYZER STARTED                  ");
        logger.info("=============================================================");

        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            logger.error("Error reading properties file: " + e.getMessage());
            throw e;
        }
        String tagName = props.getProperty(Constants.TAGNAME);
        String msgCategory = "";
        String transactionService = props.getProperty(Constants.TRANSACTION_SERVICE);
        String watchListType = props.getProperty(Constants.WATCHLIST_TYPE);
        String webServiceId = props.getProperty(Constants.WEBSERVICE_ID);
        if (transactionService.equalsIgnoreCase("SWIFT")) msgCategory = "SWIFT";
        else if (transactionService.equalsIgnoreCase("FEDWIRE")) msgCategory = "FEDWIRE";
        else if (transactionService.equalsIgnoreCase("ISO20022")) msgCategory = "SEPA";
        logger.info("tagName: " + tagName);
        processAllResponses(tagName, msgCategory, watchListType, webServiceId, matchingEngine, excelFiles);

        logger.info("\n=============================================================");
        logger.info("                   RESPONSE ANALYZER ENDED                   ");
        logger.info("=============================================================");
        long endTime = System.currentTimeMillis();

        logger.info("Time taken by Message Response Analyzer: " + (endTime - startTime) / 1000L + " seconds");
    }

    private static void processAllResponses(String tagName, String msgCategory, String watchListType, String webServiceId, String matchingEngine, List<File> excelFiles) throws Exception {
        if (excelFiles.isEmpty()) {
            logger.info("No Excel files found to analyze.");
            return;
        }

        for (File excelFile : excelFiles) {
            logger.info("Analyzing file: " + excelFile.getName());
            try (FileInputStream fis = new FileInputStream(excelFile);
                 Workbook workbook = new XSSFWorkbook(fis)) {

                Sheet sheet = workbook.getSheetAt(0);

                // Dynamically add analyzer columns
                Row headerRow = sheet.getRow(0);
                if (headerRow == null) headerRow = sheet.createRow(0);
                int lastColumn = headerRow.getLastCellNum();
                if (lastColumn < 0) lastColumn = 0;

                String[] analyzerHeaders = {
                        matchingEngine + " " + Constants.TEST_STATUS,
                        matchingEngine + " " + Constants.COMMENTS
                };
                int analyzerStartColumn = lastColumn;
                for (int i = 0; i < analyzerHeaders.length; i++) {
                    Cell headerCell = headerRow.getCell(analyzerStartColumn + i);
                    if (headerCell == null) headerCell = headerRow.createCell(analyzerStartColumn + i);
                    headerCell.setCellValue(analyzerHeaders[i]);
                }

                // Find the latest processor start column by locating the rightmost TRXN_TOKEN header
                int latestProcessorColumn = -1;
                for (int col = headerRow.getLastCellNum() - 1; col >= 0; col--) {
                    Cell cell = headerRow.getCell(col);
                    if (cell != null && cell.getStringCellValue().contains(Constants.TRXN_TOKEN)) {
                        latestProcessorColumn = col;
                        break;
                    }
                }
                if (latestProcessorColumn == -1) {
                    logger.info("No Transaction Token column found in " + excelFile.getName() + ". Skipping analysis.");
                    continue;
                }

                // Collect all transactionTokens and row data in memory using the latest processor column
                List<Long> transactionTokens = new ArrayList<>();
                Map<Long, Integer> tokenToRowNum = new HashMap<>();
                Map<Long, String> tokenToTargetColumn = new HashMap<>();
                Map<Long, String> tokenToUid = new HashMap<>();

                int rowNum = 1; // Skip header
                for (Row row : sheet) {
                    if (row.getRowNum() == 0) continue; // Skip header

                    Cell tokenCell = row.getCell(latestProcessorColumn);
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

                // Parallel processing of rows
                ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (long transactionToken : transactionTokens) {
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            JSONObject eachResponse = feedbackMap.get(transactionToken);
                            if (eachResponse == null || !eachResponse.has(Constants.MATCHES)) return;

                            JSONArray matches = eachResponse.getJSONArray(Constants.MATCHES);
                            int truePositives = 0;
                            String targetColumnName = tokenToTargetColumn.get(transactionToken);
                            String uid = tokenToUid.get(transactionToken);
                            Map<Long, String> csvColumnNamesMap = tokenToCsvColumnNamesMap.getOrDefault(transactionToken, Collections.emptyMap());

                            boolean failedDueToColumnMismatch = false;
                            for (int i = 0; i < matches.length(); i++) {
                                JSONObject match = matches.getJSONObject(i);
                                String tagNameCsv = match.optString("tagName", "");
                                Set<String> tagNames = Arrays.stream(tagNameCsv.split(",")).map(String::trim).collect(Collectors.toSet());

                                String targetUid = match.getString(Constants.MATCHED_WATCHLIST_ID);
                                Long responseId = match.getLong(Constants.RESPONSE_ID);
                                String columnNamesCsvWLS = csvColumnNamesMap.get(responseId);
                                Set<String> columnNames = columnNamesCsvWLS != null ? Arrays.stream(columnNamesCsvWLS.split(",")).collect(Collectors.toSet()) : Collections.emptySet();

                                boolean flag = uid.equals(targetUid)
                                        && watchListType.equalsIgnoreCase(match.optString("watchlistType"))
                                        && webServiceId.equalsIgnoreCase(String.valueOf(match.getInt("webServiceID")))
                                        && tagNames.contains(tagName);

                                if (flag) {
                                    if (columnNames.stream().anyMatch(col -> col.equalsIgnoreCase(targetColumnName))) { // Case-insensitive match
                                        truePositives++;
                                        failedDueToColumnMismatch = false;
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
                                Cell testStatusCell = row.getCell(analyzerStartColumn);
                                if (testStatusCell == null) testStatusCell = row.createCell(analyzerStartColumn);
                                testStatusCell.setCellValue(testStatus);
                                testStatusCell.setCellStyle(testStatus.equalsIgnoreCase(Constants.PASS) ? highlightGreen : highlightRed);

                                Cell commentsCell = row.getCell(analyzerStartColumn + 1);
                                if (commentsCell == null) commentsCell = row.createCell(analyzerStartColumn + 1);

                                if (failedDueToColumnMismatch) {
                                    commentsCell.setCellValue(Constants.COLUMN_MISMATCH_COMMENT);
                                } else if (testStatus.equalsIgnoreCase(Constants.FAIL)) {
                                    commentsCell.setCellValue(Constants.NO_MATCH_COMMENT);
                                }
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, executor);
                    futures.add(future);
                }

                // Wait for all tasks to complete
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                executor.shutdown();

                // Auto-size new columns
                for (int i = analyzerStartColumn; i < analyzerStartColumn + analyzerHeaders.length; i++) {
                    sheet.autoSizeColumn(i);
                }

                // Write the updated workbook once
                try (FileOutputStream fos = new FileOutputStream(excelFile)) {
                    workbook.write(fos);
                }

                logger.info("Analysis completed for " + excelFile.getName());
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
