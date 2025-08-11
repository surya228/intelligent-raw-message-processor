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
            String tagName = props.getProperty("tagName");
            String msgCategory = "";
            String transactionService = props.getProperty("msgPosting.transactionService");
            String watchListType = props.getProperty("watchListType");
            String webServiceId = props.getProperty("webServiceId");
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

        try (FileInputStream fis = new FileInputStream(Constants.OUTPUT_XLSX_FILE);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

            long transactionToken = 0;
            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Cell jsonCell = row.getCell(3); // 4th column --> transactionToken

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
                    if (eachResponse!=null) {
//                        System.out.println("feedback: " + eachResponse.toString());
                        processEachResponse(eachResponse, row.getRowNum(), tagName, watchListType, webServiceId, sheet, workbook);
                    }
                }
            }
        }
    }

    private static JSONObject getResponseFromFeedbackTable(long transactionToken, String msgCategory) throws Exception {
        PreparedStatement pst = null;
        ResultSet rs = null;
        Connection connection = getDbConnection();
        String query = "select C_FEEDBACK_MESSAGE from fcc_tf_feedback where N_TRAX_TOKEN = ? and V_MSG_CATEGORY = ?";
        System.out.println("SQL:: "+query);
        try {
            pst = connection.prepareStatement(query);
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
        String username = props.getProperty("username");
        String password = props.getProperty("password");
        String walletname = props.getProperty("walletName");
        String tnsAdminPath = Constants.PARENT_DIRECTORY+File.separator+"bin"+File.separator+walletname;

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.tns_admin", tnsAdminPath);
        Class.forName(jdbcDriver);
        Connection connection = DriverManager.getConnection(jdbcUrl,properties);
        System.out.println("Connection established successfully!");
        return connection;
    }


    private static void processEachResponse(JSONObject eachResponse, int rowNum, String inputTagName, String watchListType, String webServiceId, Sheet sheet, Workbook workbook) {
        System.out.println("[INFO] Processing row " + rowNum + "...");
//        System.out.println(eachResponse.toString(2));

        if (!eachResponse.has("matches")) return;
        JSONArray matches = eachResponse.getJSONArray("matches");
        int truePositives = 0;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.getJSONObject(i);
            String tagNameCsv = match.optString("tagName", "");
//            System.out.println("tagNameCsv: "+tagNameCsv);
            String[] tagNames = tagNameCsv.split(",");


            for (String tag : tagNames) {
                boolean truePositiveFlag  = inputTagName.equals(tag.trim()) && watchListType.equalsIgnoreCase(match.optString("watchlistType")) && webServiceId.equalsIgnoreCase(String.valueOf(match.getInt("webServiceID")));
                if (truePositiveFlag) {
//                    System.out.println("[MATCH] Found matching tag in row " + rowNum + ": " + inputTagName);
//                    System.out.println("Matched Data: " + match.optString("matchedData"));
//                    System.out.println("Score: " + match.optInt("score"));
//                    System.out.println("N_UID: " + match.optInt("matchedWatchlistId"));
//                    System.out.println("WatchList: " + match.optString("watchlistType"));
                    truePositives++;

                }
            }
        }
        System.out.println("-------------------------------------------------------------");
        System.out.println("No. of True Positives: " + truePositives);
        System.out.println("No. of Matches: " + matches.length());
        System.out.println("-------------------------------------------------------------");

        writeTruePositivesToExcel(rowNum, truePositives, sheet, workbook);

    }

    public static void writeTruePositivesToExcel(int rowNum, int truePositives, Sheet sheet, Workbook workbook) {
        Row row = sheet.getRow(rowNum);
        if (row == null) row = sheet.createRow(rowNum);

        Cell cell7 = row.getCell(7);
        if (cell7 == null) cell7 = row.createCell(7);
        cell7.setCellValue(truePositives);


        Cell cell8 = row.getCell(8);
        if (cell8 == null) cell8 = row.createCell(8);

        if(truePositives<=0) cell8.setCellValue(Constants.FAIL);
        else cell8.setCellValue(Constants.PASS);

        try (FileOutputStream fos = new FileOutputStream(Constants.OUTPUT_XLSX_FILE)) {
            workbook.write(fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
