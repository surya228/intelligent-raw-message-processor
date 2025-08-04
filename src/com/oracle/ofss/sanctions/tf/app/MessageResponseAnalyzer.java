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


            System.out.println("-------------------------------------------------------------");
            System.out.println("-------------------------------------------------------------");
            System.out.println("-----------------RESPONSE ANALYZER STARTED-------------------");
            System.out.println("-------------------------------------------------------------");
            System.out.println("-------------------------------------------------------------");
            String currentDir = System.getProperty("user.dir");
            File parentDir = new File(currentDir).getParentFile();
            String configFilePath = parentDir + File.separator + "bin" + File.separator + "config.properties";
            String filePath = parentDir + File.separator + "out" + File.separator + "output.xlsx";
            Properties props = new Properties();

            try (FileReader reader = new FileReader(configFilePath)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("Error reading properties file: " + e.getMessage());
                throw e;
            }
            String tagName = props.getProperty("tagName");
            String msgCategory = "";
            String transactionService = props.getProperty("msgPosting.transactionService");
            String watchListType = props.getProperty("watchListType");
            if(transactionService.equalsIgnoreCase("SWIFT")) msgCategory="SWIFT";
            else if(transactionService.equalsIgnoreCase("FEDWIRE")) msgCategory="FEDWIRE";
            else if(transactionService.equalsIgnoreCase("ISO20022")) msgCategory="SEPA";
            System.out.println("tagName: " + tagName);
            File excelFile = new File(filePath);
            processAllResponses(excelFile, tagName, msgCategory, watchListType);
            System.out.println("-------------------------------------------------------------");
            System.out.println("-------------------------------------------------------------");
            System.out.println("------------------RESPONSE ANALYZER ENDED--------------------");
            System.out.println("-------------------------------------------------------------");
            System.out.println("-------------------------------------------------------------");
        } catch (Exception e){
            e.printStackTrace();
            throw new Exception("Something went wrong while analyzing responses",e);
        }
    }

    public static void processAllResponses(File excelFile, String tagName, String msgCategory, String watchListType) throws Exception {

        try (FileInputStream fis = new FileInputStream(excelFile);
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
                        processEachResponse(eachResponse, row.getRowNum(), tagName, watchListType);
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
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");
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

        String currentDir = System.getProperty("user.dir");
        File parentDir = new File(currentDir).getParentFile();
        String configFilePath = parentDir+File.separator+"bin"+File.separator+"config.properties";
        Properties props = new Properties();
        try (FileReader reader = new FileReader(configFilePath)) {
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
        String tnsAdminPath = parentDir+File.separator+"bin"+File.separator+walletname;

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.tns_admin", tnsAdminPath);
        Class.forName(jdbcDriver);
        Connection connection = DriverManager.getConnection(jdbcUrl,properties);
        System.out.println("Connection established successfully!");
        return connection;
    }


    private static void processEachResponse(JSONObject eachResponse, int rowNum, String inputTagName, String watchListType) {
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
                if (inputTagName.equals(tag.trim()) &&
                        watchListType.equalsIgnoreCase(match.optString("watchlistType"))) {
//                    System.out.println("[MATCH] Found matching tag in row " + rowNum + ": " + inputTagName);
//                    System.out.println("Matched Data: " + match.optString("matchedData"));
//                    System.out.println("Score: " + match.optInt("score"));
//                    System.out.println("N_UID: " + match.optInt("matchedWatchlistId"));
//                    System.out.println("WatchList: " + match.optString("watchlistType"));
                    truePositives++;

                }
            }
        }

        System.out.println("No. of True Positives: " + truePositives);
        System.out.println("No. of Matches: " + matches.length());
        System.out.println("-------------------------------------------------------------");
        System.out.println("-------------------------------------------------------------");

    }


}
