package com.oracle.ofss.sanctions.tf.app;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

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
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

            long transactionToken = 0;
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Cell jsonCell = row.getCell(Constants.PROCESSOR_COLUMN_NUMBER); // transactionToken

                if (jsonCell != null) {
                    if (jsonCell.getCellType() == CellType.NUMERIC) {
                        transactionToken = (long) jsonCell.getNumericCellValue();
                    } else if (jsonCell.getCellType() == CellType.STRING) {
                        String value = jsonCell.getStringCellValue().trim();
                        if (!value.isEmpty()) {
                            transactionToken = Long.parseLong(value);
                        }
                    }
                    System.out.println("getting response from feedback table for transactionToken:   "+transactionToken);
                    JSONObject eachResponse = getResponseFromFeedbackTable(transactionToken,msgCategory);
                    int msgCategoryNumber;
                    if (msgCategory.equalsIgnoreCase("SWIFT")) msgCategoryNumber=1;
                    else if (msgCategory.equalsIgnoreCase("FEDWIRE")) msgCategoryNumber=2;
                    else msgCategoryNumber=3;
                    Map<Long,String> csvColumnNamesMap = getColumnNameCsvWLS(transactionToken,msgCategoryNumber);
                    if (eachResponse!=null) {
//                        System.out.println("feedback: " + eachResponse.toString());
                        processEachResponse(eachResponse, row.getRowNum(), tagName, watchListType, webServiceId, sheet, workbook, csvColumnNamesMap);
                    }
                }
            }
        }
    }

    private static JSONObject getResponseFromFeedbackTable(long transactionToken, String msgCategory) throws Exception {
        PreparedStatement pst = null;
        ResultSet rs = null;
        Connection connection = getDbConnection();
        try {
            pst = connection.prepareStatement(Constants.FEEDBACK_QUERY);
            pst.setLong(1, transactionToken);              // parameter 1: N_TRAX_TOKEN
            pst.setString(2, msgCategory);
            rs = pst.executeQuery();

            if (rs.next()) {
                String jsonString = rs.getString("C_FEEDBACK_MESSAGE");
                if (jsonString != null && !jsonString.isEmpty()) {
                    return new JSONObject(jsonString);
                }
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Something went wrong while preparing Query: ", e);
        } finally {
            // Always close resources in finally block
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (pst != null) try { pst.close(); } catch (Exception ignore) {}
            if (connection != null) try { connection.close(); } catch (Exception ignore) {}
        }
    }

    public static Connection getDbConnection() throws Exception {
        Properties props = new Properties();
        try (FileReader reader = new FileReader(Constants.CONFIG_FILE_PATH)) {
            props.load(reader);
        } catch (IOException e) {
            System.err.println("Error reading properties file: " + e.getMessage());
            throw e;
        }

        String jdbcUrl = props.getProperty("jdbcurl");
        String jdbcDriver = props.getProperty("jdbcdriver");
        String walletname = props.getProperty("walletName");
        String tnsAdminPath = Constants.PARENT_DIRECTORY+File.separator+Constants.BIN_FOLDER_NAME+File.separator+walletname;

        Properties properties = new Properties();
        properties.setProperty("oracle.net.tns_admin", tnsAdminPath);
        Class.forName(jdbcDriver);
        Connection connection = DriverManager.getConnection(jdbcUrl,properties);
        System.out.println("Connection established successfully!");
        return connection;
    }


    private static void processEachResponse(JSONObject eachResponse, int rowNum, String inputTagName, String watchListType, String webServiceId, Sheet sheet, Workbook workbook, Map<Long,String> csvColumnNamesMap) throws Exception {
        System.out.println("[INFO] Processing row " + rowNum + "...");
//        System.out.println("response::: "+eachResponse);

        String targetColumnName = "";
        String uid = "";

        Row row = sheet.getRow(rowNum);
        if (row != null) {

            Cell targetColumnNameCell = row.getCell(6);
            Cell uidCell = row.getCell(8);

            if (targetColumnNameCell != null)
                targetColumnName = targetColumnNameCell.getStringCellValue();

            if (uidCell != null)
                uid = uidCell.getStringCellValue();

        } else return;

        if (!eachResponse.has("matches")) return;
        JSONArray matches = eachResponse.getJSONArray("matches");
        int truePositives = 0;
        foundTruePositive:
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.getJSONObject(i);
            String tagNameCsv = match.optString("tagName", "");
            String[] tagNames = tagNameCsv.split(",");

            String targetUid = match.getString("matchedWatchlistId");

            Long responseId = match.getLong("responseID");
            String columnNamesCsvWLS = csvColumnNamesMap.get(responseId); //comma separetd value will come
            String[] columnName = columnNamesCsvWLS.split(",");

            boolean flag = uid.equals(targetUid)
                    && watchListType.equalsIgnoreCase(match.optString("watchlistType"))
                    && webServiceId.equalsIgnoreCase(String.valueOf(match.getInt("webServiceID")));

            if(flag){ // if nuid, watchlistType and webServiceId matches then check for further scenario
                for (String tag : tagNames) {
                    if(inputTagName.equals(tag.trim())) { // if tagName matches then check for further scenario
                        for (String wlColumn : columnName) {
                            boolean truePositiveFlag = targetColumnName.equalsIgnoreCase(wlColumn); // if column name matches from wls table
                            if (truePositiveFlag) {
                                truePositives++;
                                break foundTruePositive;
                            }
                        }
                    }
                }
            }




        }
        String testStatus = truePositives!=0?Constants.PASS:Constants.FAIL;
        System.out.println("-------------------------------------------------------------");
        System.out.println("ANALYZER TEST STATUS " + testStatus);
        System.out.println("No. of Matches: " + matches.length());
        System.out.println("-------------------------------------------------------------");

        writeTruePositivesToExcel(rowNum, truePositives, sheet, workbook, testStatus);

    }

    private static Map<Long, String> getColumnNameCsvWLS(long transactionToken, int msgCategory) throws Exception {
        Map<Long, String> columnNamesMap = new HashMap<>();
        PreparedStatement pst = null;
        ResultSet rs = null;
        Connection connection = getDbConnection();
        try {
            pst = connection.prepareStatement(Constants.WLS_RESPONSE_QUERY);
            pst.setLong(1, transactionToken);
            pst.setLong(2, msgCategory);
            rs = pst.executeQuery();

            while (rs.next()) {
                long responseId = rs.getLong("N_RESPONSE_ID");
                String columnName = rs.getString("V_COLUMN_NAME");
                columnNamesMap.put(responseId,columnName);
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw new Exception("Something went wrong while preparing Query: ", e);
        } finally {
            // Always close resources in finally block
            if (rs != null) try { rs.close(); } catch (Exception ignore) {}
            if (pst != null) try { pst.close(); } catch (Exception ignore) {}
            if (connection != null) try { connection.close(); } catch (Exception ignore) {}
        }
        return columnNamesMap.isEmpty() ? null : columnNamesMap;
    }

    public static void writeTruePositivesToExcel(int rowNum, int truePositives, Sheet sheet, Workbook workbook, String testStatus) {
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);

        CellStyle passStyle = workbook.createCellStyle();
        passStyle.setFillForegroundColor(IndexedColors.BRIGHT_GREEN.getIndex());
        passStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        passStyle.setFont(boldFont);

        CellStyle failStyle = workbook.createCellStyle();
        failStyle.setFillForegroundColor(IndexedColors.RED.getIndex());
        failStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        failStyle.setFont(boldFont);

        Row row = sheet.getRow(rowNum);
        if (row == null) row = sheet.createRow(rowNum);

        Cell testStatusCell = row.getCell(Constants.ANALYZER_COLUMN_NUMBER);
        if (testStatusCell == null) testStatusCell = row.createCell(Constants.ANALYZER_COLUMN_NUMBER);

        testStatusCell.setCellValue(testStatus);

        if(testStatus.equalsIgnoreCase(Constants.PASS)){
            testStatusCell.setCellStyle(passStyle);
        } else {
            testStatusCell.setCellStyle(failStyle);
        }

        try (FileOutputStream fos = new FileOutputStream(Constants.OUTPUT_XLSX_FILE_PATH)) {
            workbook.write(fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
