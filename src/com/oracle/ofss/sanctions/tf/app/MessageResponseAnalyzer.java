package com.oracle.ofss.sanctions.tf.app;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.Properties;

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
            System.out.println("tagName: " + tagName);
            File excelFile = new File(filePath);
            processEachJsonInExcel(excelFile, tagName);
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

    public static void processEachJsonInExcel(File excelFile, String tagName) throws Exception {

        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) rowIterator.next();

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Cell jsonCell = row.getCell(3); // 4th column --> response

                if (jsonCell != null && jsonCell.getCellType() == CellType.STRING) {
                    String jsonText = jsonCell.getStringCellValue().trim();
                    try {
                        JSONObject eachResponse = new JSONObject(jsonText);
                        processJson(eachResponse, row.getRowNum(),tagName);
                    } catch (Exception e) {
                        System.err.println("[WARN] Invalid JSON at row " + (row.getRowNum() + 1) + ": " + e.getMessage());
                        throw new Exception(e);
                    }
                }
            }
        }
    }

    private static void processJson(JSONObject eachResponse, int rowNum, String inputTagName) {
        System.out.println("[INFO] Processing row " + rowNum + "...");
//        System.out.println(eachResponse.toString(2));

        if (!eachResponse.has("feedbackData")) return;
        JSONObject feedbackData = eachResponse.getJSONObject("feedbackData");
        if (!feedbackData.has("matches")) return;
        JSONArray matches = feedbackData.getJSONArray("matches");

        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.getJSONObject(i);
            String tagNameCsv = match.optString("tagName", "");
//            System.out.println("tagNameCsv: "+tagNameCsv);
            String[] tagNames = tagNameCsv.split(",");

            for (String tag : tagNames) {
                if (inputTagName.equals(tag.trim())) {
                    System.out.println("[MATCH] Found matching tag in row " + rowNum + ": " + inputTagName);
                    System.out.println("Matched Data: " + match.optString("matchedData"));
                    System.out.println("Score: " + match.optInt("score"));
                    System.out.println("N_UID: " + match.optInt("matchedWatchlistId"));
                    System.out.println("WatchList: " + match.optString("watchlistType"));

                }
            }
        }

        System.out.println("[NO MATCH] Tag not found in row " + rowNum + ": " + inputTagName);

    }


}
